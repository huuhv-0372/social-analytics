package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.client.soap.ExchangeRateQuote;
import com.huuhv.socialanalytics.client.soap.ExchangeRateSoapClient;
import com.huuhv.socialanalytics.client.soap.ExchangeRateUnavailableException;
import com.huuhv.socialanalytics.config.properties.AppProperties;
import com.huuhv.socialanalytics.dto.response.ExchangeRateResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Tỷ giá cho dashboard, lấy từ dịch vụ SOAP ({@link ExchangeRateSoapClient}) với cache trong bộ nhớ của instance
 * (dữ liệu chung cho mọi user, không thuộc về ai — không cần cache dùng chung giữa các instance).
 * <ul>
 *     <li>Request (dashboard) <b>chỉ đọc cache</b> ({@link #getCurrentRate}), không bao giờ gọi SOAP: trước đó request
 *     gặp cache hết hạn phải chờ SOAP (tới connect + read timeout) trên thread Tomcat, mỗi {@code cache-ttl} ở mỗi
 *     instance; lời gọi tới endpoint mock của chính app còn cần thêm 1 thread Tomcat rảnh nên dễ timeout khi tải
 *     cao.</li>
 *     <li>Việc gọi SOAP chạy nền: {@code scheduler/RefreshExchangeRateJob} gọi {@link #refreshIfDue} theo lịch
 *     {@code app.jobs.refresh-exchange-rate.cron}; chỉ gọi khi giá trị cũ hơn {@code cache-ttl} và không đang trong
 *     {@code retry-after-failure} sau lần lỗi; đang làm mới thì lần gọi khác bỏ qua ({@code tryLock}). Mỗi instance
 *     tự làm mới cache của mình (không ShedLock).</li>
 *     <li>App sẵn sàng → lấy sẵn 1 lần ở thread nền ({@code warm-up-on-startup}) để request đầu tiên đã có giá
 *     trị.</li>
 *     <li>Gọi lỗi (timeout, Fault, response sai...) → log WARN, giữ giá trị cũ.</li>
 *     <li>{@code stale = true} ("Dữ liệu cũ") khi lần gọi gần nhất lỗi, <b>hoặc</b> giá trị đã quá 2 × {@code cache-ttl}
 *     mà chưa được làm mới (job không chạy / bị chặn) — dashboard không hiển thị số cũ như số mới.</li>
 * </ul>
 * Không bao giờ ném exception: dashboard luôn hiển thị được, phần tỷ giá báo không lấy được ({@code null}).
 * Không cấu hình {@code app.soap.exchange-rate.url} = tắt tỷ giá: luôn {@code null}, không gọi SOAP.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeRateService {

    /** Giá trị chưa được làm mới quá số lần {@code cache-ttl} này thì coi là cũ (job làm mới không chạy). */
    static final int STALE_AFTER_TTL_MULTIPLIER = 2;

    private final ExchangeRateSoapClient exchangeRateSoapClient;
    private final AppProperties appProperties;
    private final Clock clock;

    private final ReentrantLock refreshLock = new ReentrantLock();
    /** Lần lấy thành công gần nhất; {@code null} = chưa lấy được lần nào. */
    private volatile CachedRate cached;
    /** Lần gọi lỗi gần nhất, luôn sau lần thành công gần nhất; {@code null} = lần gọi gần nhất thành công. */
    private volatile Instant lastFailureAt;

    private record CachedRate(ExchangeRateQuote quote, Instant fetchedAt) {
    }

    /**
     * Chỉ đọc cache — không gọi SOAP, không chờ khoá.
     *
     * @return tỷ giá (có thể là giá trị cũ, {@code stale = true}) hoặc {@code null} khi chưa từng lấy được / tỷ giá bị
     * tắt
     */
    public ExchangeRateResponse getCurrentRate() {
        if (!appProperties.soap().exchangeRate().hasUrl()) {
            return null;
        }
        return toResponse(cached);
    }

    /**
     * Gọi SOAP làm mới cache nếu đến hạn: chưa có giá trị hoặc giá trị cũ hơn {@code cache-ttl}, và không đang trong
     * {@code retry-after-failure} sau lần lỗi. Thread khác đang làm mới → bỏ qua (không chờ). Không ném exception.
     */
    public void refreshIfDue() {
        if (!appProperties.soap().exchangeRate().hasUrl() || !isDue()) {
            return;
        }
        if (!refreshLock.tryLock()) {
            return;
        }
        try {
            // Kiểm tra lại: thread giữ khoá trước có thể vừa lấy xong / vừa lỗi
            if (isDue()) {
                refresh();
            }
        } finally {
            refreshLock.unlock();
        }
    }

    /**
     * Lấy sẵn tỷ giá khi app vừa sẵn sàng (Tomcat đã nhận request — endpoint mock của chính app gọi được), ở thread
     * của executor mặc định ({@code @Async}) để không làm chậm khởi động. Lỗi được xử lý như lần làm mới thường (WARN +
     * backoff).
     */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        AppProperties.ExchangeRate settings = appProperties.soap().exchangeRate();
        if (!settings.hasUrl()) {
            log.info("Exchange rate is disabled: app.soap.exchange-rate.url is not set");
            return;
        }
        if (settings.warmUpOnStartup()) {
            refreshIfDue();
        }
    }

    private boolean isDue() {
        CachedRate current = cached;
        return (current == null || !isFresh(current)) && !isBackingOff();
    }

    /** Chỉ gọi khi đang giữ {@link #refreshLock}. */
    private void refresh() {
        AppProperties.ExchangeRate settings = appProperties.soap().exchangeRate();
        try {
            ExchangeRateQuote quote = exchangeRateSoapClient.getExchangeRate(settings.baseCurrency(),
                    settings.quoteCurrency());
            cached = new CachedRate(quote, clock.instant());
            lastFailureAt = null;
            return;
        } catch (ExchangeRateUnavailableException e) {
            // Message của exception này chỉ là mô tả chung (không chứa dữ liệu của server bên kia)
            log.warn("Cannot fetch exchange rate {}/{}: {}", settings.baseCurrency(), settings.quoteCurrency(),
                    e.getMessage());
        } catch (RuntimeException e) {
            // Lỗi không lường trước (bug) — vẫn không làm hỏng dashboard, nhưng ghi đủ stack trace để sửa
            log.error("Unexpected error while fetching exchange rate {}/{}", settings.baseCurrency(),
                    settings.quoteCurrency(), e);
        }
        lastFailureAt = clock.instant();
    }

    private boolean isFresh(CachedRate rate) {
        return clock.instant().isBefore(rate.fetchedAt().plus(appProperties.soap().exchangeRate().cacheTtl()));
    }

    private boolean isBackingOff() {
        Instant failedAt = lastFailureAt;
        return failedAt != null
                && clock.instant().isBefore(failedAt.plus(appProperties.soap().exchangeRate().retryAfterFailure()));
    }

    private ExchangeRateResponse toResponse(CachedRate rate) {
        if (rate == null) {
            return null;
        }
        // Lấy thành công thì xoá mốc lỗi → còn mốc lỗi nghĩa là lần gọi gần nhất (sau lần thành công) đã lỗi
        boolean stale = lastFailureAt != null || isOverdue(rate);
        ExchangeRateQuote quote = rate.quote();
        return new ExchangeRateResponse(quote.baseCurrency(), quote.quoteCurrency(), quote.rate(), quote.asOf(),
                rate.fetchedAt(), stale);
    }

    /** Quá {@value #STALE_AFTER_TTL_MULTIPLIER} × {@code cache-ttl} chưa được làm mới (job không chạy). */
    private boolean isOverdue(CachedRate rate) {
        return !clock.instant().isBefore(rate.fetchedAt()
                .plus(appProperties.soap().exchangeRate().cacheTtl().multipliedBy(STALE_AFTER_TTL_MULTIPLIER)));
    }
}

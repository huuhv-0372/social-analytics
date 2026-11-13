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
 *     <li>Còn trong {@code app.soap.exchange-rate.cache-ttl} → dùng lại, không gọi SOAP.</li>
 *     <li>Hết hạn → gọi lại; mỗi instance chỉ 1 lời gọi tại 1 thời điểm. Trong lúc đang gọi, request khác <b>không
 *     chờ</b>: dùng ngay giá trị cũ, chưa có giá trị thì trả {@code null}. Chờ khoá sẽ giữ thread Tomcat, trong khi
 *     lời gọi SOAP tới endpoint mock của chính app cũng cần 1 thread Tomcat rảnh — nhiều request cùng chờ (vd ngay sau
 *     restart) làm lời gọi đó timeout và cả app nghẽn.</li>
 *     <li>App sẵn sàng → lấy sẵn 1 lần ở thread nền ({@code warm-up-on-startup}) để request đầu tiên đã có giá
 *     trị.</li>
 *     <li>Gọi lỗi (timeout, Fault, response sai...) → log WARN, giữ giá trị cũ và đánh dấu {@code stale}; không thử
 *     lại trong {@code retry-after-failure} để trang không phải chờ timeout mỗi lần tải.</li>
 * </ul>
 * Không bao giờ ném exception: dashboard luôn hiển thị được, phần tỷ giá báo không lấy được ({@code null}).
 * Không cấu hình {@code app.soap.exchange-rate.url} = tắt tỷ giá: luôn {@code null}, không gọi SOAP.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeRateService {

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
     * @return tỷ giá (có thể là giá trị cũ, {@code stale = true}) hoặc {@code null} khi chưa từng lấy được
     */
    public ExchangeRateResponse getCurrentRate() {
        if (!appProperties.soap().exchangeRate().hasUrl()) {
            return null;
        }
        CachedRate current = cached;
        if ((current != null && isFresh(current)) || isBackingOff()) {
            return toResponse(current);
        }
        // Thread khác đang lấy → không chờ: dùng giá trị hiện có (null khi chưa có lần nào thành công)
        if (!refreshLock.tryLock()) {
            return toResponse(current);
        }
        try {
            // Kiểm tra lại: thread giữ khoá trước có thể vừa lấy xong / vừa lỗi
            current = cached;
            if ((current != null && isFresh(current)) || isBackingOff()) {
                return toResponse(current);
            }
            return toResponse(refresh());
        } finally {
            refreshLock.unlock();
        }
    }

    /**
     * Lấy sẵn tỷ giá khi app vừa sẵn sàng (Tomcat đã nhận request — endpoint mock của chính app gọi được), ở thread
     * của executor mặc định ({@code @Async}) để không làm chậm khởi động. Lỗi được xử lý như lần gọi thường (WARN +
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
            getCurrentRate();
        }
    }

    /** Chỉ gọi khi đang giữ {@link #refreshLock}. Trả giá trị mới, hoặc giá trị cũ khi lỗi. */
    private CachedRate refresh() {
        AppProperties.ExchangeRate settings = appProperties.soap().exchangeRate();
        try {
            ExchangeRateQuote quote = exchangeRateSoapClient.getExchangeRate(settings.baseCurrency(),
                    settings.quoteCurrency());
            CachedRate fresh = new CachedRate(quote, clock.instant());
            cached = fresh;
            lastFailureAt = null;
            return fresh;
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
        return cached;
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
        boolean stale = lastFailureAt != null;
        ExchangeRateQuote quote = rate.quote();
        return new ExchangeRateResponse(quote.baseCurrency(), quote.quoteCurrency(), quote.rate(), quote.asOf(),
                rate.fetchedAt(), stale);
    }
}

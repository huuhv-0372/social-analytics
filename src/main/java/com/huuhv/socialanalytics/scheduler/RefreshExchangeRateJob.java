package com.huuhv.socialanalytics.scheduler;

import com.huuhv.socialanalytics.service.ExchangeRateService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Làm mới cache tỷ giá của instance này ở thread nền, để request dashboard chỉ đọc cache và không bao giờ phải chờ
 * SOAP ({@link ExchangeRateService}).
 * <p>
 * Lịch {@code app.jobs.refresh-exchange-rate.cron} (mặc định mỗi 30 giây) chỉ quyết định độ trễ sau khi cache hết hạn:
 * {@link ExchangeRateService#refreshIfDue} chỉ gọi SOAP khi giá trị cũ hơn {@code app.soap.exchange-rate.cache-ttl}
 * (hoặc đã hết {@code retry-after-failure} sau lần lỗi). Mỗi instance có cache riêng nên tự chạy job — <b>không</b>
 * dùng ShedLock (khoá chung sẽ làm các instance khác không bao giờ được làm mới). Tỷ giá bị tắt (không cấu hình url)
 * → không làm gì. Job chỉ gọi Service; Service không ném exception.
 */
@Component
@RequiredArgsConstructor
public class RefreshExchangeRateJob {

    private final ExchangeRateService exchangeRateService;

    @Scheduled(cron = "${app.jobs.refresh-exchange-rate.cron:*/30 * * * * *}")
    public void refreshExchangeRateJob() {
        exchangeRateService.refreshIfDue();
    }
}

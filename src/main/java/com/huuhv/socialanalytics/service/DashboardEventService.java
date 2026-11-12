package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.enums.DashboardEventType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * Báo "dữ liệu dashboard đã đổi" cho client realtime. Chỉ phát sự kiện trong JVM ({@link DashboardChangedEvent});
 * gửi đi (JMS topic → WebSocket) do {@code messaging/DashboardEventProducer} làm sau khi transaction của caller commit:
 * gọi trong transaction bị rollback thì không có event nào, client không refetch dữ liệu chưa commit.
 * <p>
 * Không ném lỗi gửi ra caller — event chỉ là tín hiệu làm mới, dữ liệu đã được ghi.
 */
@Service
@RequiredArgsConstructor
public class DashboardEventService {

    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /** Job crawl metrics đã cập nhật xong (1 event cho cả lần chạy, không phải mỗi tài khoản). */
    public void metricsUpdated() {
        publish(DashboardEventType.METRICS_UPDATED);
    }

    /** 1 lần import vừa được cộng vào thống kê — gọi trong transaction cộng thống kê. */
    public void importProcessed() {
        publish(DashboardEventType.IMPORT_PROCESSED);
    }

    private void publish(DashboardEventType type) {
        eventPublisher.publishEvent(new DashboardChangedEvent(type, clock.instant()));
    }
}

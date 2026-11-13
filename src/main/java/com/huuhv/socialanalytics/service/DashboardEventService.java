package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.enums.DashboardEventType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Collection;
import java.util.Set;

/**
 * Báo "dữ liệu dashboard đã đổi" cho client realtime của <b>đúng các user bị ảnh hưởng</b> (không broadcast: user khác
 * không phải gọi lại API vô ích, không biết thời điểm import của người khác). Chỉ phát sự kiện trong JVM
 * ({@link DashboardChangedEvent}); gửi đi (JMS topic → WebSocket theo user) do {@code messaging/DashboardEventProducer}
 * làm sau khi transaction của caller commit: gọi trong transaction bị rollback thì không có event nào.
 * <p>
 * Không ném lỗi gửi ra caller — event chỉ là tín hiệu làm mới, dữ liệu đã được ghi.
 */
@Service
@RequiredArgsConstructor
public class DashboardEventService {

    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /**
     * Job crawl metrics đã cập nhật tài khoản của các user này (1 lần gọi cho cả lần chạy, không phải mỗi tài khoản).
     * Tập rỗng → không làm gì.
     */
    public void metricsUpdated(Collection<Long> userIds) {
        if (!userIds.isEmpty()) {
            publish(DashboardEventType.METRICS_UPDATED, Set.copyOf(userIds));
        }
    }

    /** 1 lần import của {@code ownerId} vừa được cộng vào thống kê — gọi trong transaction cộng thống kê. */
    public void importProcessed(long ownerId) {
        publish(DashboardEventType.IMPORT_PROCESSED, Set.of(ownerId));
    }

    private void publish(DashboardEventType type, Set<Long> userIds) {
        eventPublisher.publishEvent(new DashboardChangedEvent(type, clock.instant(), userIds));
    }
}

package com.huuhv.socialanalytics.messaging;

import com.huuhv.socialanalytics.service.DashboardChangedEvent;
import jakarta.jms.Topic;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * Phát {@link DashboardChangedEvent} lên topic {@value TopicNames#DASHBOARD_EVENTS}: user có thể đang nối WebSocket
 * vào bất kỳ instance nào, mỗi instance nhận 1 bản và chỉ đẩy xuống user đang kết nối với nó
 * ({@link DashboardEventListener}).
 * <ul>
 *     <li>{@code AFTER_COMMIT}: client nhận event rồi gọi API phải thấy dữ liệu đã commit; transaction rollback → không
 *     gửi. {@code fallbackExecution = true}: job metrics phát event ngoài transaction → gửi ngay.</li>
 *     <li>Danh sách user chia lô {@link DashboardEventMessage#MAX_USER_IDS} id/message (job cập nhật nhiều tài khoản).</li>
 *     <li>Gửi tới {@link Topic} tường minh — {@code JmsTemplate} của Boot mặc định coi tên chuỗi là queue
 *     ({@code spring.jms.pub-sub-domain=false}, không đổi vì các queue của Epic 5 dùng chung template).</li>
 *     <li>Gửi lỗi → log, không ném: dữ liệu đã ghi, event chỉ là tín hiệu làm mới (client tải lại khi có event sau /
 *     khi kết nối lại). Không retry, không outbox.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashboardEventProducer {

    private final JmsTemplate jmsTemplate;
    private final Topic dashboardEventsTopic;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDashboardChanged(DashboardChangedEvent event) {
        List<Long> userIds = event.userIds().stream().sorted().toList();
        try {
            for (int from = 0; from < userIds.size(); from += DashboardEventMessage.MAX_USER_IDS) {
                List<Long> batch = userIds.subList(from,
                        Math.min(from + DashboardEventMessage.MAX_USER_IDS, userIds.size()));
                jmsTemplate.convertAndSend(dashboardEventsTopic,
                        new DashboardEventMessage(event.type(), event.occurredAt(), List.copyOf(batch)));
            }
            log.info("Published dashboard event {} for {} users to topic {}", event.type(), userIds.size(),
                    TopicNames.DASHBOARD_EVENTS);
        } catch (RuntimeException e) {
            log.error("Failed to publish dashboard event {} for {} users to topic {}; clients refresh on the next event",
                    event.type(), userIds.size(), TopicNames.DASHBOARD_EVENTS, e);
        }
    }
}

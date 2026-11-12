package com.huuhv.socialanalytics.messaging;

import com.huuhv.socialanalytics.service.DashboardChangedEvent;
import jakarta.jms.Topic;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Phát {@link DashboardChangedEvent} lên topic {@value TopicNames#DASHBOARD_EVENTS} để mọi instance đẩy xuống client
 * WebSocket của mình ({@link DashboardEventListener}).
 * <ul>
 *     <li>{@code AFTER_COMMIT}: client nhận event rồi gọi API phải thấy dữ liệu đã commit; transaction rollback → không
 *     gửi. {@code fallbackExecution = true}: job metrics phát event ngoài transaction → gửi ngay.</li>
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
        try {
            jmsTemplate.convertAndSend(dashboardEventsTopic, DashboardEventMessage.from(event));
            log.info("Published dashboard event {} to topic {}", event.type(), TopicNames.DASHBOARD_EVENTS);
        } catch (RuntimeException e) {
            log.error("Failed to publish dashboard event {} to topic {}; clients refresh on the next event",
                    event.type(), TopicNames.DASHBOARD_EVENTS, e);
        }
    }
}

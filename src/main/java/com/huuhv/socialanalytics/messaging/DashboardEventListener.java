package com.huuhv.socialanalytics.messaging;

import com.huuhv.socialanalytics.websocket.DashboardEventBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

/**
 * Mỗi instance nhận event trên topic {@value TopicNames#DASHBOARD_EVENTS} (subscription non-durable riêng) rồi đẩy
 * xuống client WebSocket nối vào chính instance đó — simple broker của STOMP chỉ biết subscription trong RAM của nó.
 * <p>
 * Factory {@value TopicNames#LISTENER_CONTAINER_FACTORY}: không transacted — lỗi khi đẩy xuống WebSocket không được
 * broker giao lại (event lỗi thời ngay sau đó, client tải lại khi có event mới); lỗi được
 * {@code JmsListenerErrorHandler} log.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashboardEventListener {

    /** Id container trong {@code JmsListenerEndpointRegistry} (test chờ subscription đăng ký xong). */
    public static final String LISTENER_ID = "dashboardEventListener";

    private final DashboardEventBroadcaster broadcaster;

    @JmsListener(id = LISTENER_ID, destination = TopicNames.DASHBOARD_EVENTS,
            containerFactory = TopicNames.LISTENER_CONTAINER_FACTORY)
    public void onDashboardEvent(DashboardEventMessage message) {
        // Payload từ broker là dữ liệu không tin cậy (A08): thiếu field → bỏ, không đẩy event rỗng xuống client
        if (message.type() == null || message.occurredAt() == null) {
            log.warn("Dropping dashboard event without type or occurredAt");
            return;
        }
        broadcaster.broadcast(message.type(), message.occurredAt());
    }
}

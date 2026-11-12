package com.huuhv.socialanalytics.websocket;

import com.huuhv.socialanalytics.enums.DashboardEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Đẩy event "dữ liệu dashboard đã đổi" xuống mọi client STOMP đang SUBSCRIBE {@value StompDestinations#METRICS_TOPIC}
 * trên instance này. Không gọi trực tiếp từ Service: event đi qua JMS topic trước ({@code messaging/DashboardEventListener})
 * để tới cả client nối vào instance khác.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashboardEventBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;

    public void broadcast(DashboardEventType type, Instant occurredAt) {
        messagingTemplate.convertAndSend(StompDestinations.METRICS_TOPIC, new DashboardEventPayload(type, occurredAt));
        log.debug("Broadcast dashboard event {} to {}", type, StompDestinations.METRICS_TOPIC);
    }
}

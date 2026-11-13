package com.huuhv.socialanalytics.websocket;

import com.huuhv.socialanalytics.enums.DashboardEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;

/**
 * Đẩy event "dữ liệu dashboard của bạn đã đổi" xuống từng user trên {@value StompDestinations#USER_METRICS_DESTINATION}.
 * Chỉ tới được user đang có session STOMP trên <b>instance này</b>: {@code UserDestinationMessageHandler} tra
 * {@code SimpUserRegistry} và bỏ message của user không kết nối (user nối vào instance khác do instance đó gửi, vì mọi
 * instance đều nhận message JMS). Không gọi trực tiếp từ Service: event đi qua JMS topic trước
 * ({@code messaging/DashboardEventListener}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashboardEventBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * @param userIds id user nội bộ — trùng tên principal STOMP
     */
    public void sendToUsers(DashboardEventType type, Instant occurredAt, Collection<Long> userIds) {
        DashboardEventPayload payload = new DashboardEventPayload(type, occurredAt);
        for (Long userId : userIds) {
            messagingTemplate.convertAndSendToUser(String.valueOf(userId), StompDestinations.METRICS_QUEUE, payload);
        }
        log.debug("Dashboard event {} forwarded for {} users", type, userIds.size());
    }
}

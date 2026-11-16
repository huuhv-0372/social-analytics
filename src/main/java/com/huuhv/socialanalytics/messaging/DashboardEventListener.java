package com.huuhv.socialanalytics.messaging;

import com.huuhv.socialanalytics.websocket.DashboardEventBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Mỗi instance nhận event trên topic {@value TopicNames#DASHBOARD_EVENTS} (subscription non-durable riêng) rồi đẩy
 * xuống các user trong message <b>đang nối WebSocket vào chính instance này</b> — registry user của STOMP chỉ biết
 * session trong RAM của nó.
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
        // Payload từ broker là dữ liệu không tin cậy (A08): thiếu field, id không hợp lệ hoặc quá nhiều id → bỏ cả message
        List<Long> userIds = message.userIds();
        if (message.type() == null || message.occurredAt() == null || userIds == null || userIds.isEmpty()
                || userIds.size() > DashboardEventMessage.MAX_USER_IDS
                || !userIds.stream().allMatch(id -> Objects.nonNull(id) && id > 0)) {
            log.warn("Dropping invalid dashboard event message");
            return;
        }
        broadcaster.sendToUsers(message.type(), message.occurredAt(), userIds);
    }
}

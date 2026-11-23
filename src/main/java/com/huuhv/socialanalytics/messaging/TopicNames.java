package com.huuhv.socialanalytics.messaging;

/**
 * Tên JMS topic (publish/subscribe — mỗi subscriber nhận 1 bản) dùng chung cho producer, {@code @JmsListener} và cấu
 * hình broker ({@code artemis/broker.properties}, đối chiếu trong {@code ArtemisEmbeddedConfigTest}).
 */
public final class TopicNames {

    /**
     * Event "dữ liệu dashboard đã đổi" ({@link DashboardEventMessage}). Address multicast: mọi instance app có 1
     * subscription non-durable riêng → event phát ở instance nào (job giữ khoá ShedLock, listener nhận message import)
     * cũng tới client WebSocket nối vào mọi instance. Instance đang tắt thì bỏ lỡ event — client tự tải lại dữ liệu
     * khi kết nối lại.
     */
    public static final String DASHBOARD_EVENTS = "dashboard-events";

    /**
     * Listener container factory cho topic (pub/sub, non-durable, không transacted, 1 consumer) — khai báo ở
     * {@code config/JmsConfig}. Factory mặc định dùng cho queue.
     */
    public static final String LISTENER_CONTAINER_FACTORY = "topicListenerContainerFactory";

    private TopicNames() {
    }
}

package com.huuhv.socialanalytics.websocket;

/**
 * Đường dẫn STOMP dùng chung giữa cấu hình ({@code config/WebSocketConfig}, {@code config/WebSocketSecurityConfig},
 * {@code config/SecurityConfig}), publisher và trang dashboard ({@code data-metrics-destination}) — không hardcode chuỗi
 * rải rác.
 */
public final class StompDestinations {

    /** Endpoint handshake (SockJS): {@code /ws/info}, {@code /ws/{server}/{session}/{transport}}. */
    public static final String ENDPOINT = "/ws";

    /** Mọi đường dẫn HTTP của endpoint SockJS. */
    public static final String ENDPOINT_PATTERN = ENDPOINT + "/**";

    /** Prefix frame SEND tới {@code @MessageMapping} — hiện chưa có handler nào nên mọi SEND bị từ chối. */
    public static final String APPLICATION_PREFIX = "/app";

    /** Prefix của simple broker (server → client). Client không SUBSCRIBE trực tiếp dưới prefix này. */
    public static final String BROKER_PREFIX = "/queue";

    /** Prefix đích riêng từng user: {@code /user/queue/x} → {@code /queue/x-user{sessionId}} của đúng session đó. */
    public static final String USER_PREFIX = "/user";

    /** Đích phía server khi gửi cho 1 user ({@code convertAndSendToUser(userId, METRICS_QUEUE, …)}). */
    public static final String METRICS_QUEUE = BROKER_PREFIX + "/metrics";

    /**
     * Đích duy nhất client được SUBSCRIBE: event "dữ liệu dashboard của bạn đã đổi". Spring chỉ giao tới session của
     * chính user đó (tên principal STOMP = id user nội bộ — {@code config/StompUserIdChannelInterceptor}).
     */
    public static final String USER_METRICS_DESTINATION = USER_PREFIX + METRICS_QUEUE;

    private StompDestinations() {
    }
}

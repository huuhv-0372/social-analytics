package com.huuhv.socialanalytics.websocket;

/**
 * Đường dẫn STOMP dùng chung giữa cấu hình ({@code config/WebSocketConfig}, {@code config/WebSocketSecurityConfig},
 * {@code config/SecurityConfig}) và publisher — không hardcode chuỗi rải rác.
 */
public final class StompDestinations {

    /** Endpoint handshake (SockJS): {@code /ws/info}, {@code /ws/{server}/{session}/{transport}}. */
    public static final String ENDPOINT = "/ws";

    /** Mọi đường dẫn HTTP của endpoint SockJS. */
    public static final String ENDPOINT_PATTERN = ENDPOINT + "/**";

    /** Prefix frame SEND tới {@code @MessageMapping} — hiện chưa có handler nào nên mọi SEND bị từ chối. */
    public static final String APPLICATION_PREFIX = "/app";

    /** Prefix của simple broker (server → client). */
    public static final String BROKER_PREFIX = "/topic";

    /** Topic duy nhất client được SUBSCRIBE: event "dữ liệu dashboard đã đổi" (không chứa số liệu/userId). */
    public static final String METRICS_TOPIC = BROKER_PREFIX + "/metrics";

    private StompDestinations() {
    }
}

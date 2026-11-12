package com.huuhv.socialanalytics.config;

import com.huuhv.socialanalytics.security.StompAccessDeniedErrorHandler;
import com.huuhv.socialanalytics.websocket.StompDestinations;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * STOMP over WebSocket (SockJS) cho dashboard realtime: endpoint {@value StompDestinations#ENDPOINT}, app prefix
 * {@value StompDestinations#APPLICATION_PREFIX}, simple broker {@value StompDestinations#BROKER_PREFIX}.
 * <ul>
 *     <li>Handshake là request HTTP đi qua filter chain của {@link SecurityConfig}: chưa đăng nhập → 401. Phân quyền
 *     frame + CSRF ở frame CONNECT: {@link WebSocketSecurityConfig}.</li>
 *     <li>Không gọi {@code setAllowedOriginPatterns}: Spring chỉ nhận handshake cùng origin (chống Cross-Site
 *     WebSocket Hijacking).</li>
 *     <li>Giới hạn kích thước frame client gửi lên (A06): client chỉ gửi CONNECT/SUBSCRIBE, vài trăm byte.</li>
 *     <li>Lỗi xử lý frame (gồm bị từ chối) → ERROR frame chung chung + log ({@link StompAccessDeniedErrorHandler}).</li>
 *     <li>Simple broker giữ subscription trong RAM từng instance — chạy nhiều instance thì event phải được phát tới
 *     mọi instance trước khi gửi xuống client.</li>
 * </ul>
 * {@code @EnableWebSocketMessageBroker} đăng ký bean {@code TaskScheduler} {@code messageBrokerTaskScheduler}; vì vậy
 * {@link AsyncConfig} phải tự khai báo {@code taskScheduler} cho {@code @Scheduled} (xem javadoc ở đó).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /** Heartbeat STOMP 2 chiều — phát hiện kết nối chết sau proxy/NAT (khớp mặc định 10s của {@code @stomp/stompjs}). */
    static final long HEARTBEAT_MILLIS = 10_000;

    /** Frame lớn nhất client được gửi (mặc định của Spring là 64 KB). */
    static final int MESSAGE_SIZE_LIMIT_BYTES = 8 * 1024;

    /** Buffer gửi cho 1 client chậm trước khi bị ngắt — payload event chỉ vài chục byte. */
    static final int SEND_BUFFER_SIZE_LIMIT_BYTES = 64 * 1024;

    static final int SEND_TIME_LIMIT_MILLIS = 10_000;

    private final TaskScheduler messageBrokerTaskScheduler;

    /**
     * {@code @Lazy}: scheduler do chính cấu hình message broker tạo ra (tránh vòng phụ thuộc khi khởi tạo); qualifier vì
     * context có 2 {@code TaskScheduler} ({@code taskScheduler} của {@link AsyncConfig}).
     */
    public WebSocketConfig(@Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler messageBrokerTaskScheduler) {
        this.messageBrokerTaskScheduler = messageBrokerTaskScheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(StompDestinations.ENDPOINT).withSockJS();
        registry.setErrorHandler(new StompAccessDeniedErrorHandler());
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes(StompDestinations.APPLICATION_PREFIX);
        registry.enableSimpleBroker(StompDestinations.BROKER_PREFIX)
                .setHeartbeatValue(new long[]{HEARTBEAT_MILLIS, HEARTBEAT_MILLIS})
                .setTaskScheduler(messageBrokerTaskScheduler);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(MESSAGE_SIZE_LIMIT_BYTES)
                .setSendBufferSizeLimit(SEND_BUFFER_SIZE_LIMIT_BYTES)
                .setSendTimeLimit(SEND_TIME_LIMIT_MILLIS);
    }
}

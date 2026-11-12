package com.huuhv.socialanalytics.config;

import com.huuhv.socialanalytics.websocket.StompDestinations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.socket.EnableWebSocketSecurity;
import org.springframework.security.messaging.access.intercept.MessageMatcherDelegatingAuthorizationManager;

/**
 * Phân quyền frame STOMP (OWASP A01) — deny-by-default như {@link SecurityConfig}:
 * <ul>
 *     <li>CONNECT / HEARTBEAT / UNSUBSCRIBE / DISCONNECT: phải đăng nhập (principal lấy từ session HTTP lúc handshake).</li>
 *     <li>SUBSCRIBE chỉ cho {@value StompDestinations#METRICS_TOPIC}.</li>
 *     <li>Mọi frame còn lại bị từ chối — đặc biệt SEND vào {@code /topic/**}: simple broker chuyển thẳng frame SEND
 *     tới mọi subscriber, nếu cho phép thì 1 user bắn được event giả tới dashboard của mọi user khác. SEND
 *     {@code /app/**} cũng bị chặn vì chưa có {@code @MessageMapping}.</li>
 * </ul>
 * {@code @EnableWebSocketSecurity} còn gắn {@code XorCsrfChannelInterceptor}: frame CONNECT phải có header
 * {@code X-XSRF-TOKEN} chứa token <b>đã mask</b> ({@code <meta name="_csrf">}), so với token raw đọc từ cookie
 * {@code XSRF-TOKEN} lúc handshake ({@code CsrfTokenHandshakeInterceptor}). Thiếu/sai → ERROR frame, đóng kết nối.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocketSecurity
public class WebSocketSecurityConfig {

    @Bean
    AuthorizationManager<Message<?>> messageAuthorizationManager(
            MessageMatcherDelegatingAuthorizationManager.Builder messages) {
        return authorizationRules(messages).build();
    }

    /** Tách riêng để test kiểm tra đúng bộ quy tắc mà bean dùng. */
    static MessageMatcherDelegatingAuthorizationManager.Builder authorizationRules(
            MessageMatcherDelegatingAuthorizationManager.Builder messages) {
        return messages
                .simpTypeMatchers(SimpMessageType.CONNECT, SimpMessageType.HEARTBEAT,
                        SimpMessageType.UNSUBSCRIBE, SimpMessageType.DISCONNECT).authenticated()
                .simpSubscribeDestMatchers(StompDestinations.METRICS_TOPIC).authenticated()
                .anyMessage().denyAll();
    }
}

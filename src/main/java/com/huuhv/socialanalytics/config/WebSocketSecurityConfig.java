package com.huuhv.socialanalytics.config;

import com.huuhv.socialanalytics.security.CsrfHeaderOrMaskedChannelInterceptor;
import com.huuhv.socialanalytics.websocket.StompDestinations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.socket.EnableWebSocketSecurity;
import org.springframework.security.messaging.access.intercept.MessageMatcherDelegatingAuthorizationManager;

/**
 * Phân quyền frame STOMP (OWASP A01) — deny-by-default như {@link SecurityConfig}:
 * <ul>
 *     <li>CONNECT / HEARTBEAT / UNSUBSCRIBE / DISCONNECT: phải đăng nhập (principal lấy từ session HTTP lúc handshake).</li>
 *     <li>SUBSCRIBE chỉ cho {@value StompDestinations#USER_METRICS_DESTINATION} (Spring dịch sang hàng đợi riêng của
 *     chính session đó). SUBSCRIBE thẳng {@code /queue/**} bị chặn — tránh đoán tên hàng đợi của session khác.</li>
 *     <li>Mọi frame còn lại bị từ chối — đặc biệt SEND vào {@code /queue/**} / {@code /user/**}: simple broker chuyển
 *     thẳng frame SEND tới subscriber, nếu cho phép thì 1 user bắn được event giả tới dashboard của user khác. SEND
 *     {@code /app/**} cũng bị chặn vì chưa có {@code @MessageMapping}.</li>
 * </ul>
 * CSRF ở frame CONNECT: header {@code X-XSRF-TOKEN} so với token đọc từ cookie {@code XSRF-TOKEN} lúc handshake
 * ({@code CsrfTokenHandshakeInterceptor}) — nhận token đã mask ({@code <meta name="_csrf">}) <b>hoặc</b> raw (giá trị
 * cookie, JS đọc mỗi lần kết nối lại nên vẫn đúng sau khi cookie đổi), giống header HTTP
 * ({@link CsrfHeaderOrMaskedChannelInterceptor}). Thiếu/sai → ERROR frame, đóng kết nối.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocketSecurity
public class WebSocketSecurityConfig {

    @Bean
    AuthorizationManager<Message<?>> messageAuthorizationManager(
            MessageMatcherDelegatingAuthorizationManager.Builder messages) {
        return authorizationRules(messages).build();
    }

    /**
     * Thay {@code XorCsrfChannelInterceptor} mặc định của {@code @EnableWebSocketSecurity} (cấu hình của Spring lấy bean
     * theo đúng tên {@code csrfChannelInterceptor}).
     */
    @Bean(name = "csrfChannelInterceptor")
    ChannelInterceptor csrfChannelInterceptor() {
        return new CsrfHeaderOrMaskedChannelInterceptor();
    }

    /** Tách riêng để test kiểm tra đúng bộ quy tắc mà bean dùng. */
    static MessageMatcherDelegatingAuthorizationManager.Builder authorizationRules(
            MessageMatcherDelegatingAuthorizationManager.Builder messages) {
        return messages
                .simpTypeMatchers(SimpMessageType.CONNECT, SimpMessageType.HEARTBEAT,
                        SimpMessageType.UNSUBSCRIBE, SimpMessageType.DISCONNECT).authenticated()
                .simpSubscribeDestMatchers(StompDestinations.USER_METRICS_DESTINATION).authenticated()
                .anyMessage().denyAll();
    }
}

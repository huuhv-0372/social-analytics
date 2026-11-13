package com.huuhv.socialanalytics.config;

import com.huuhv.socialanalytics.exception.AuthenticationRequiredException;
import com.huuhv.socialanalytics.security.AppOAuth2User;
import com.huuhv.socialanalytics.security.StompUserAuthentication;
import com.huuhv.socialanalytics.service.CurrentUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

/**
 * Đổi principal của phiên STOMP ở frame {@code CONNECT} thành {@link StompUserAuthentication} mang <b>id user nội
 * bộ</b> — event gửi theo user ({@code convertAndSendToUser(userId, …)}) tới đúng người.
 * <p>
 * Làm ở tầng STOMP (không ở handshake) để áp dụng cho <b>mọi transport SockJS</b>: {@code HandshakeHandler} chỉ được
 * transport {@code websocket} dùng; {@code xhr-streaming}/{@code xhr-polling} lấy thẳng principal của request HTTP
 * (tên = id phía provider). Spring ghi nhớ user đặt ở frame CONNECT cho cả phiên (registry user, frame sau).
 * <ul>
 *     <li>Principal {@link AppOAuth2User} → lấy {@code userId} trong principal, không query: request mang frame này
 *     vừa qua {@code StaleSessionFilter} (đã kiểm tra user còn tồn tại).</li>
 *     <li>Principal OAuth2 kiểu cũ → tra DB qua {@link CurrentUserService#getUser}; không xác định được → từ chối
 *     CONNECT ({@link AccessDeniedException} → ERROR frame chung, fail closed).</li>
 *     <li>Ẩn danh → giữ nguyên, luật phân quyền frame ({@link WebSocketSecurityConfig}) từ chối.</li>
 * </ul>
 * Phải chạy <b>trước</b> các interceptor của Spring Security ({@link StompUserInterceptorConfig}). Đặt ở
 * {@code config/} vì phụ thuộc {@code service}/{@code exception} (giống {@code StaleSessionFilter}).
 */
@Slf4j
class StompUserIdChannelInterceptor implements ChannelInterceptor {

    private final CurrentUserService currentUserService;

    StompUserIdChannelInterceptor(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() != StompCommand.CONNECT) {
            return message;
        }
        if (accessor.getUser() instanceof Authentication authentication && authentication.isAuthenticated()) {
            accessor.setUser(new StompUserAuthentication(authentication, internalUserId(authentication)));
        }
        return message;
    }

    private long internalUserId(Authentication authentication) {
        if (authentication.getPrincipal() instanceof AppOAuth2User user) {
            return user.getUserId();
        }
        try {
            return currentUserService.getUser(authentication).getId();
        } catch (AuthenticationRequiredException e) {
            log.warn("STOMP CONNECT from a session without a known user; connection rejected");
            throw new AccessDeniedException("STOMP session has no known user");
        }
    }
}

package com.huuhv.socialanalytics.security;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.messaging.web.csrf.XorCsrfChannelInterceptor;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Kiểm CSRF ở frame STOMP {@code CONNECT}, nhận token ở cả 2 dạng như header HTTP
 * ({@link CsrfHeaderOrFormTokenRequestHandler}):
 * <ul>
 *     <li><b>đã mask</b> — {@code <meta name="_csrf">} của trang (kiểm bằng {@link XorCsrfChannelInterceptor} của Spring);</li>
 *     <li><b>raw</b> — giá trị hiện tại của cookie {@code XSRF-TOKEN}: trang mở lâu, cookie đã đổi (đăng nhập lại ở tab
 *     khác) thì token trong {@code <meta>} hết hiệu lực; JS đọc cookie mỗi lần kết nối lại nên không phải tải lại
 *     trang. Cookie {@code SameSite=Lax} + chỉ JS cùng origin đọc được nên trang khác không lấy được giá trị này.</li>
 * </ul>
 * Token mong đợi là token raw đọc từ cookie lúc handshake ({@code CsrfTokenHandshakeInterceptor}). Thiếu → {@link
 * MissingCsrfTokenException}, sai → {@link InvalidCsrfTokenException} (cả 2 là {@code AccessDeniedException} → ERROR
 * frame chung, đóng kết nối). So sánh raw bằng {@link MessageDigest#isEqual} (constant-time) — {@code
 * CsrfChannelInterceptor} của Spring dùng {@code String.equals}.
 */
public final class CsrfHeaderOrMaskedChannelInterceptor implements ChannelInterceptor {

    private final ChannelInterceptor masked = new XorCsrfChannelInterceptor();

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        if (SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) != SimpMessageType.CONNECT) {
            return message;
        }
        Map<String, Object> sessionAttributes = SimpMessageHeaderAccessor.getSessionAttributes(message.getHeaders());
        CsrfToken expected = sessionAttributes == null ? null
                : (CsrfToken) sessionAttributes.get(CsrfToken.class.getName());
        if (expected == null) {
            throw new MissingCsrfTokenException(null);
        }
        String actual = SimpMessageHeaderAccessor.wrap(message).getFirstNativeHeader(expected.getHeaderName());
        if (actual != null && (matchesRaw(expected, actual) || matchesMasked(message, channel))) {
            return message;
        }
        throw new InvalidCsrfTokenException(expected, actual);
    }

    private static boolean matchesRaw(CsrfToken expected, String actual) {
        return MessageDigest.isEqual(expected.getToken().getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private boolean matchesMasked(Message<?> message, MessageChannel channel) {
        try {
            masked.preSend(message, channel);
            return true;
        } catch (InvalidCsrfTokenException | IllegalArgumentException e) {
            // Không phải token đã mask hợp lệ (độ dài sai, không giải được) — đã thử raw ở trên
            return false;
        }
    }
}

package com.huuhv.socialanalytics.security;

import com.huuhv.socialanalytics.util.LogSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

import java.security.Principal;

/**
 * ERROR frame trả cho client khi frame STOMP không xử lý được (OWASP A09/A10):
 * <ul>
 *     <li>Bị từ chối (thiếu quyền, thiếu/sai CSRF ở CONNECT) → log WARN như {@link LoggingAccessDeniedHandler}
 *     (lệnh, destination đã bỏ CR/LF, user) rồi trả thông điệp chung {@value #ACCESS_DENIED_MESSAGE}.</li>
 *     <li>Lỗi khác → thông điệp chung {@value #GENERIC_ERROR_MESSAGE}, log chỉ tên loại exception.</li>
 * </ul>
 * Handler mặc định của Spring đưa nguyên {@code ex.getMessage()} vào ERROR frame — với CSRF đó là chuỗi chứa lại token
 * client gửi lên và tên header; không trả chi tiết nội bộ ra client. Không log token/header.
 * Sau ERROR frame, Spring đóng kết nối WebSocket.
 */
@Slf4j
public class StompAccessDeniedErrorHandler extends StompSubProtocolErrorHandler {

    static final String ACCESS_DENIED_MESSAGE = "Access denied";
    static final String GENERIC_ERROR_MESSAGE = "Message could not be processed";

    private static final byte[] EMPTY_PAYLOAD = new byte[0];

    /** Exception bị bọc trong {@code MessageDeliveryException} — giới hạn độ sâu khi dò cause (chống vòng lặp cause). */
    private static final int MAX_CAUSE_DEPTH = 10;

    @Override
    public @Nullable Message<byte[]> handleClientMessageProcessingError(@Nullable Message<byte[]> clientMessage,
                                                                        Throwable ex) {
        StompHeaderAccessor client = clientMessage == null ? null : StompHeaderAccessor.wrap(clientMessage);
        AccessDeniedException denied = findAccessDenied(ex);
        String command = client == null || client.getCommand() == null ? "unknown" : client.getCommand().name();
        if (denied != null) {
            String reason = denied instanceof CsrfException ? "missing or invalid CSRF token" : "access denied";
            log.warn("STOMP forbidden ({}): {} {} user={}", reason, command,
                    LogSanitizer.stripLineBreaks(client == null ? null : client.getDestination()), userName(client));
        } else {
            log.warn("STOMP frame rejected: {} ({})", command, ex.getClass().getSimpleName());
        }

        StompHeaderAccessor error = StompHeaderAccessor.create(StompCommand.ERROR);
        error.setMessage(denied != null ? ACCESS_DENIED_MESSAGE : GENERIC_ERROR_MESSAGE);
        if (client != null && client.getReceipt() != null) {
            error.setReceiptId(client.getReceipt());
        }
        return MessageBuilder.createMessage(EMPTY_PAYLOAD, error.getMessageHeaders());
    }

    private static @Nullable AccessDeniedException findAccessDenied(Throwable ex) {
        Throwable current = ex;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof AccessDeniedException denied) {
                return denied;
            }
            current = current.getCause();
        }
        return null;
    }

    private static String userName(@Nullable StompHeaderAccessor client) {
        Principal user = client == null ? null : SimpMessageHeaderAccessor.getUser(client.getMessageHeaders());
        return user == null ? "anonymous" : LogSanitizer.stripLineBreaks(user.getName());
    }
}

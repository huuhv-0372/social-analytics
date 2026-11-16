package com.huuhv.socialanalytics.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;

import static org.assertj.core.api.Assertions.assertThat;

class StompAccessDeniedErrorHandlerTest {

    private static final String SENT_TOKEN = "client-sent-token-value";

    private final StompAccessDeniedErrorHandler handler = new StompAccessDeniedErrorHandler();

    private final Logger handlerLogger = (Logger) LoggerFactory.getLogger(StompAccessDeniedErrorHandler.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void attachLogAppender() {
        logs.start();
        handlerLogger.addAppender(logs);
    }

    @AfterEach
    void detachLogAppender() {
        handlerLogger.detachAppender(logs);
    }

    @Test
    void invalidCsrfTokenOnConnectReturnsGenericErrorAndLogsSecurityEvent() {
        Message<byte[]> connect = clientFrame(StompCommand.CONNECT, null, "receipt-1");
        // Spring bọc exception của interceptor trong MessageDeliveryException; thông điệp gốc chứa lại token client gửi
        InvalidCsrfTokenException csrf = new InvalidCsrfTokenException(
                new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "expected"), SENT_TOKEN);
        Exception ex = new MessageDeliveryException(connect, csrf);

        Message<byte[]> error = handler.handleClientMessageProcessingError(connect, ex);

        StompHeaderAccessor headers = StompHeaderAccessor.wrap(error);
        assertThat(headers.getCommand()).isEqualTo(StompCommand.ERROR);
        assertThat(headers.getMessage()).isEqualTo(StompAccessDeniedErrorHandler.ACCESS_DENIED_MESSAGE);
        assertThat(headers.getReceiptId()).isEqualTo("receipt-1");
        assertThat(error.getPayload()).isEmpty();
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .contains("missing or invalid CSRF token", "CONNECT", "user=owner")
                    .doesNotContain(SENT_TOKEN, "expected");
        });
    }

    @Test
    void deniedSubscriptionLogsSanitizedDestination() {
        Message<byte[]> subscribe = clientFrame(StompCommand.SUBSCRIBE, "/topic/other\r\nINJECTED", null);

        Message<byte[]> error = handler.handleClientMessageProcessingError(subscribe,
                new MessageDeliveryException(subscribe, new AccessDeniedException("Access Denied")));

        assertThat(StompHeaderAccessor.wrap(error).getMessage())
                .isEqualTo(StompAccessDeniedErrorHandler.ACCESS_DENIED_MESSAGE);
        assertThat(logs.list).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                .contains("(access denied): SUBSCRIBE /topic/other__INJECTED user=owner")
                .doesNotContain("\n", "\r"));
    }

    @Test
    void otherErrorsReturnGenericMessageWithoutInternalDetails() {
        Message<byte[]> send = clientFrame(StompCommand.SEND, "/app/x", null);

        Message<byte[]> error = handler.handleClientMessageProcessingError(send,
                new IllegalStateException("internal detail"));

        assertThat(StompHeaderAccessor.wrap(error).getMessage())
                .isEqualTo(StompAccessDeniedErrorHandler.GENERIC_ERROR_MESSAGE);
        assertThat(logs.list).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                .contains("SEND", "IllegalStateException")
                .doesNotContain("internal detail"));
    }

    @Test
    void decodingErrorWithoutClientMessageStillReturnsErrorFrame() {
        Message<byte[]> error = handler.handleClientMessageProcessingError(null, new IllegalArgumentException("bad"));

        StompHeaderAccessor headers = StompHeaderAccessor.wrap(error);
        assertThat(headers.getCommand()).isEqualTo(StompCommand.ERROR);
        assertThat(headers.getMessage()).isEqualTo(StompAccessDeniedErrorHandler.GENERIC_ERROR_MESSAGE);
    }

    private static Message<byte[]> clientFrame(StompCommand command, String destination, String receipt) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (receipt != null) {
            accessor.setReceipt(receipt);
        }
        accessor.setUser(new TestingAuthenticationToken("owner", null));
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}

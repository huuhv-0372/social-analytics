package com.huuhv.socialanalytics.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class CsrfHeaderOrMaskedChannelInterceptorTest {

    private static final String HEADER = "X-XSRF-TOKEN";
    private static final CsrfToken EXPECTED = new DefaultCsrfToken(HEADER, "_csrf", "4b2e1c8a-7d1f-4f0e-9a51-3c6a1b2d9e70");

    private final CsrfHeaderOrMaskedChannelInterceptor interceptor = new CsrfHeaderOrMaskedChannelInterceptor();
    private final MessageChannel channel = mock(MessageChannel.class);

    @Test
    void acceptsMaskedTokenFromPageMeta() {
        Message<byte[]> connect = connect(maskedToken());

        assertThat(interceptor.preSend(connect, channel)).isSameAs(connect);
    }

    @Test
    void acceptsRawTokenFromCurrentCookie() {
        Message<byte[]> connect = connect(EXPECTED.getToken());

        assertThat(interceptor.preSend(connect, channel)).isSameAs(connect);
    }

    @ParameterizedTest
    @ValueSource(strings = {"forged", "4b2e1c8a-7d1f-4f0e-9a51-3c6a1b2d9e71", "", "%%%not-base64%%%"})
    void rejectsWrongToken(String token) {
        assertThatThrownBy(() -> interceptor.preSend(connect(token), channel))
                .isInstanceOf(InvalidCsrfTokenException.class);
    }

    @Test
    void rejectsMissingHeader() {
        assertThatThrownBy(() -> interceptor.preSend(connect(null), channel))
                .isInstanceOf(InvalidCsrfTokenException.class);
    }

    @Test
    void rejectsSessionWithoutExpectedToken() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionAttributes(new HashMap<>());
        accessor.setNativeHeader(HEADER, EXPECTED.getToken());
        Message<byte[]> connect = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(connect, channel)).isInstanceOf(MissingCsrfTokenException.class);
    }

    @Test
    void framesOtherThanConnectAreNotChecked() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/user/queue/metrics");
        Message<byte[]> subscribe = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThat(interceptor.preSend(subscribe, channel)).isSameAs(subscribe);
    }

    private static Message<byte[]> connect(String token) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        Map<String, Object> sessionAttributes = new HashMap<>();
        sessionAttributes.put(CsrfToken.class.getName(), EXPECTED);
        accessor.setSessionAttributes(sessionAttributes);
        if (token != null) {
            accessor.setNativeHeader(HEADER, token);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    /** Token đã mask đúng như Spring render vào {@code <meta name="_csrf">}. */
    private static String maskedToken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        new XorCsrfTokenRequestAttributeHandler().handle(request, new MockHttpServletResponse(), () -> EXPECTED);
        return ((CsrfToken) request.getAttribute(CsrfToken.class.getName())).getToken();
    }
}

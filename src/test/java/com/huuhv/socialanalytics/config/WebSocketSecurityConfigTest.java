package com.huuhv.socialanalytics.config;

import com.huuhv.socialanalytics.websocket.StompDestinations;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.messaging.access.intercept.MessageMatcherDelegatingAuthorizationManager;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class WebSocketSecurityConfigTest {

    private static final Authentication USER = new TestingAuthenticationToken("owner", null, "OAUTH2_USER");
    private static final Authentication ANONYMOUS = new AnonymousAuthenticationToken("key", "anonymousUser",
            AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

    private final AuthorizationManager<Message<?>> manager = WebSocketSecurityConfig
            .authorizationRules(MessageMatcherDelegatingAuthorizationManager.builder())
            .build();

    static Stream<Arguments> allowedForLoggedInUser() {
        return Stream.of(
                arguments(SimpMessageType.CONNECT, null),
                arguments(SimpMessageType.HEARTBEAT, null),
                arguments(SimpMessageType.UNSUBSCRIBE, null),
                arguments(SimpMessageType.DISCONNECT, null),
                arguments(SimpMessageType.SUBSCRIBE, StompDestinations.METRICS_TOPIC));
    }

    @ParameterizedTest
    @MethodSource("allowedForLoggedInUser")
    void loggedInUserCanConnectAndSubscribeToMetrics(SimpMessageType type, String destination) {
        assertThat(isGranted(USER, frame(type, destination))).isTrue();
    }

    @ParameterizedTest
    @MethodSource("allowedForLoggedInUser")
    void anonymousUserIsDeniedEverything(SimpMessageType type, String destination) {
        assertThat(isGranted(ANONYMOUS, frame(type, destination))).isFalse();
    }

    static Stream<Arguments> deniedForLoggedInUser() {
        return Stream.of(
                // SEND thẳng vào topic → broker phát tới mọi dashboard (event giả)
                arguments(SimpMessageType.MESSAGE, StompDestinations.METRICS_TOPIC),
                arguments(SimpMessageType.MESSAGE, "/topic/other"),
                // Chưa có @MessageMapping
                arguments(SimpMessageType.MESSAGE, "/app/anything"),
                arguments(SimpMessageType.MESSAGE, null),
                arguments(SimpMessageType.SUBSCRIBE, "/topic/other"),
                arguments(SimpMessageType.SUBSCRIBE, StompDestinations.METRICS_TOPIC + "/child"),
                arguments(SimpMessageType.SUBSCRIBE, "/topic/**"),
                arguments(SimpMessageType.SUBSCRIBE, "/app/anything"),
                arguments(SimpMessageType.SUBSCRIBE, "/user/queue/errors"),
                arguments(SimpMessageType.SUBSCRIBE, null),
                // ACK/NACK/BEGIN/COMMIT/ABORT — simple broker không hỗ trợ
                arguments(SimpMessageType.OTHER, null));
    }

    @ParameterizedTest
    @MethodSource("deniedForLoggedInUser")
    void loggedInUserCannotSendOrSubscribeElsewhere(SimpMessageType type, String destination) {
        assertThat(isGranted(USER, frame(type, destination))).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = SimpMessageType.class, names = {"CONNECT_ACK", "DISCONNECT_ACK"})
    void serverOnlyFrameTypesAreDenied(SimpMessageType type) {
        assertThat(isGranted(USER, frame(type, null))).isFalse();
    }

    private boolean isGranted(Authentication authentication, Message<?> message) {
        AuthorizationResult result = manager.authorize(() -> authentication, message);
        return result != null && result.isGranted();
    }

    private static Message<byte[]> frame(SimpMessageType type, String destination) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(type);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}

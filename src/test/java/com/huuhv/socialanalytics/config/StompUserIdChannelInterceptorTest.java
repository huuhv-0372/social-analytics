package com.huuhv.socialanalytics.config;

import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.exception.AuthenticationRequiredException;
import com.huuhv.socialanalytics.security.AppOAuth2User;
import com.huuhv.socialanalytics.security.StompUserAuthentication;
import com.huuhv.socialanalytics.service.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;

import java.security.Principal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StompUserIdChannelInterceptorTest {

    @Mock
    private CurrentUserService currentUserService;

    @Test
    void connectPrincipalNameBecomesInternalUserIdWithoutQueryingDatabase() {
        // Id phía provider ("1234567890") có thể trùng giữa Facebook và X — gửi theo user dùng id nội bộ
        AppOAuth2User appUser = new AppOAuth2User(AuthorityUtils.createAuthorityList("OAUTH2_USER"),
                Map.of("id", "1234567890"), "id", 42L, "Owner");
        Authentication login = new OAuth2AuthenticationToken(appUser, appUser.getAuthorities(), "facebook");

        Principal user = userAfterPreSend(StompCommand.CONNECT, login);

        assertThat(user).isInstanceOfSatisfying(StompUserAuthentication.class, stompUser -> {
            assertThat(stompUser.getName()).isEqualTo("42");
            assertThat(stompUser.isAuthenticated()).isTrue();
            assertThat(stompUser.getAuthorities()).isEqualTo(login.getAuthorities());
            assertThat(stompUser.getPrincipal()).isSameAs(appUser);
            assertThat(stompUser.getCredentials()).isNull();
        });
        // StaleSessionFilter đã kiểm tra user còn tồn tại cho request mang frame này
        verifyNoInteractions(currentUserService);
    }

    @Test
    void legacyOAuth2PrincipalIsResolvedThroughCurrentUserService() {
        Authentication login = new TestingAuthenticationToken("1234567890", "secret", "OAUTH2_USER");
        when(currentUserService.getUser(login)).thenReturn(User.builder().id(7L).build());

        assertThat(userAfterPreSend(StompCommand.CONNECT, login)).extracting(Principal::getName).isEqualTo("7");
    }

    @Test
    void unknownUserIsDeniedSoConnectFailsClosed() {
        Authentication login = new TestingAuthenticationToken("1234567890", null, "OAUTH2_USER");
        when(currentUserService.getUser(login)).thenThrow(new AuthenticationRequiredException());

        assertThatThrownBy(() -> userAfterPreSend(StompCommand.CONNECT, login))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anonymousConnectIsLeftForAuthorizationRulesToDeny() {
        assertThat(userAfterPreSend(StompCommand.CONNECT, null)).isNull();
        verifyNoInteractions(currentUserService);
    }

    @Test
    void framesOtherThanConnectAreUntouched() {
        Authentication login = new TestingAuthenticationToken("1234567890", null, "OAUTH2_USER");

        assertThat(userAfterPreSend(StompCommand.SUBSCRIBE, login)).isSameAs(login);
        verifyNoInteractions(currentUserService);
    }

    private Principal userAfterPreSend(StompCommand command, Principal sessionUser) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setUser(sessionUser);
        accessor.setLeaveMutable(true);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = new StompUserIdChannelInterceptor(currentUserService)
                .preSend(message, mock(MessageChannel.class));

        return SimpMessageHeaderAccessor.getUser(result.getHeaders());
    }
}

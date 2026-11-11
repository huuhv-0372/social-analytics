package com.huuhv.socialanalytics.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.WebAttributes;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OAuth2LoginResultHandlerTest {

    private final OAuth2LoginResultHandler handler =
            new OAuth2LoginResultHandler(SecurityConfig.LOGIN_SUCCESS_URL, SecurityConfig.LOGIN_FAILURE_URL);

    @Test
    void successAlwaysRedirectsToDashboardIgnoringTargetParameter() throws Exception {
        OAuth2User principal = new DefaultOAuth2User(AuthorityUtils.createAuthorityList("OAUTH2_USER"),
                Map.of("id", "1234567890", "name", "Dev"), "id");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("redirect", "https://evil.example");
        request.setParameter("continue", "https://evil.example");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response,
                new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "facebook"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/dashboard");
    }

    @Test
    void failureRedirectsToLoginErrorWithoutDetails() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(new MockHttpServletRequest(), response,
                new OAuth2AuthenticationException(new OAuth2Error("access_denied\r\nforged log line")));

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error");
    }

    @Test
    void failureDoesNotStoreExceptionInSession() throws Exception {
        // Session lưu MySQL: exception (cause tuỳ ý) không được serialize vào session
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);

        handler.onAuthenticationFailure(request, new MockHttpServletResponse(), new OAuth2AuthenticationException(
                new OAuth2Error("server_error"), new IllegalStateException("db down")));

        assertThat(session.getAttribute(WebAttributes.AUTHENTICATION_EXCEPTION)).isNull();
        assertThat(session.getAttributeNames().hasMoreElements()).isFalse();
    }

    @Test
    void failureDoesNotCreateSession() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();

        handler.onAuthenticationFailure(request, new MockHttpServletResponse(),
                new OAuth2AuthenticationException(new OAuth2Error("access_denied")));

        assertThat(request.getSession(false)).isNull();
    }
}

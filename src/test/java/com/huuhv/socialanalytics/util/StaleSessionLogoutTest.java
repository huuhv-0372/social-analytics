package com.huuhv.socialanalytics.util;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StaleSessionLogoutTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(StaleSessionLogout.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void attachLogAppender() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(logs);
        SecurityContextHolder.clearContext();
    }

    @Test
    void invalidatesSessionClearsContextAndLogsProviderOnly() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        Authentication authentication = new OAuth2AuthenticationToken(
                new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("id", "42"), "id"),
                AuthorityUtils.createAuthorityList("ROLE_USER"), "facebook");
        SecurityContextHolder.getContext().setAuthentication(authentication);

        StaleSessionLogout.logout(request, new MockHttpServletResponse(), authentication);

        assertThat(session.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(logs.list).singleElement().extracting(ILoggingEvent::getFormattedMessage).asString()
                .contains("facebook")
                .doesNotContain(session.getId());
    }

    @Test
    void nonOAuth2AuthenticationIsStillLoggedOut() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);

        StaleSessionLogout.logout(request, new MockHttpServletResponse(), new TestingAuthenticationToken("u", "p"));

        assertThat(session.isInvalid()).isTrue();
        assertThat(logs.list).singleElement().extracting(ILoggingEvent::getFormattedMessage).asString()
                .contains("unknown");
    }
}

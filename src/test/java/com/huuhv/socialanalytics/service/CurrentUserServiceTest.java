package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.enums.Provider;
import com.huuhv.socialanalytics.exception.AuthenticationRequiredException;
import com.huuhv.socialanalytics.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CurrentUserServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CurrentUserService currentUserService;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsWhenNoAuthentication() {
        assertThatThrownBy(() -> currentUserService.getCurrentUser())
                .isInstanceOf(AuthenticationRequiredException.class);
        verifyNoInteractions(userRepository);
    }

    @Test
    void rejectsAnonymousAuthentication() {
        authenticate(new AnonymousAuthenticationToken("key", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThatThrownBy(() -> currentUserService.getCurrentUser())
                .isInstanceOf(AuthenticationRequiredException.class);
        verifyNoInteractions(userRepository);
    }

    @Test
    void mapsOAuth2PrincipalToUserThroughSocialAccount() {
        User owner = User.builder().id(1L).displayName("Dev").build();
        authenticate(oauth2Token("facebook", "1234567890"));
        when(userRepository.findBySocialAccount(Provider.FACEBOOK, "1234567890")).thenReturn(Optional.of(owner));

        assertThat(currentUserService.getCurrentUser()).isSameAs(owner);
    }

    @Test
    void mapsXRegistrationToTwitterProvider() {
        User owner = User.builder().id(2L).displayName("Dev").build();
        authenticate(oauth2Token("twitter", "2244994945"));
        when(userRepository.findBySocialAccount(Provider.TWITTER, "2244994945")).thenReturn(Optional.of(owner));

        assertThat(currentUserService.getCurrentUser()).isSameAs(owner);
    }

    @Test
    void failsClosedWhenSocialAccountIsNotStored() {
        authenticate(oauth2Token("facebook", "1234567890"));
        when(userRepository.findBySocialAccount(Provider.FACEBOOK, "1234567890")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> currentUserService.getCurrentUser())
                .isInstanceOf(AuthenticationRequiredException.class);
    }

    @Test
    void rejectsUnknownRegistrationId() {
        authenticate(oauth2Token("github", "42"));

        assertThatThrownBy(() -> currentUserService.getCurrentUser())
                .isInstanceOf(AuthenticationRequiredException.class);
        verifyNoInteractions(userRepository);
    }

    @Test
    void rejectsNonOAuth2Authentication() {
        // Không còn map username (HTTP Basic / form) sang User theo email
        authenticate(UsernamePasswordAuthenticationToken.authenticated("dev@example.com", null,
                AuthorityUtils.NO_AUTHORITIES));

        assertThatThrownBy(() -> currentUserService.getCurrentUser())
                .isInstanceOf(AuthenticationRequiredException.class);
        verifyNoInteractions(userRepository);
    }

    private static OAuth2AuthenticationToken oauth2Token(String registrationId, String providerUserId) {
        OAuth2User principal = new DefaultOAuth2User(AuthorityUtils.createAuthorityList("OAUTH2_USER"),
                Map.of("id", providerUserId, "name", "Dev"), "id");
        return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), registrationId);
    }

    private static void authenticate(Authentication authentication) {
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}

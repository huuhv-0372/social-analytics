package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.exception.AuthenticationRequiredException;
import com.huuhv.socialanalytics.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CurrentUserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserProvisioningService userProvisioningService;

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
    void returnsExistingUser() {
        User existing = User.builder().id(1L).email("dev@example.com").displayName("Dev").build();
        authenticateAs("dev@example.com");
        when(userRepository.findByEmail("dev@example.com")).thenReturn(Optional.of(existing));

        assertThat(currentUserService.getCurrentUser()).isSameAs(existing);
        verifyNoInteractions(userProvisioningService);
    }

    @Test
    void getCurrentUserNeverProvisionsAndFailsClosedWhenUserMissing() {
        authenticateAs("missing@example.com");
        when(userRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> currentUserService.getCurrentUser())
                .isInstanceOf(AuthenticationRequiredException.class);
        verifyNoInteractions(userProvisioningService);
    }

    // TẠM THỜI tới Task 3.4: principal OAuth2 (tên = id phía provider) không được map/tạo User theo email
    @Test
    void oauth2PrincipalIsNotMappedByNameUntilSocialAccountExists() {
        OAuth2User principal = new DefaultOAuth2User(AuthorityUtils.createAuthorityList("OAUTH2_USER"),
                Map.of("id", "1234567890", "name", "Dev"), "id");
        authenticate(new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "facebook"));

        assertThatThrownBy(() -> currentUserService.getCurrentUser())
                .isInstanceOf(AuthenticationRequiredException.class);
        currentUserService.provisionCurrentUserIfMissing();

        verifyNoInteractions(userRepository, userProvisioningService);
    }

    @Test
    void provisionSkipsAnonymousRequest() {
        currentUserService.provisionCurrentUserIfMissing();

        verifyNoInteractions(userProvisioningService);
    }

    @Test
    void provisionSkipsExistingUser() {
        authenticateAs("dev@example.com");
        when(userProvisioningService.findByEmail("dev@example.com"))
                .thenReturn(Optional.of(User.builder().id(1L).build()));

        currentUserService.provisionCurrentUserIfMissing();

        verify(userProvisioningService, never()).provision(any());
    }

    @Test
    void provisionsUserOnFirstAccess() {
        authenticateAs("new@example.com");
        when(userProvisioningService.findByEmail("new@example.com")).thenReturn(Optional.empty());

        currentUserService.provisionCurrentUserIfMissing();

        verify(userProvisioningService).provision("new@example.com");
    }

    @Test
    void concurrentFirstAccessToleratesUserCreatedByOtherRequest() {
        authenticateAs("race@example.com");
        when(userProvisioningService.findByEmail("race@example.com"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(User.builder().id(3L).build()));
        when(userProvisioningService.provision("race@example.com"))
                .thenThrow(new DataIntegrityViolationException("uk_users_email"));

        assertThatCode(() -> currentUserService.provisionCurrentUserIfMissing()).doesNotThrowAnyException();
    }

    @Test
    void rethrowsWhenProvisioningFailsForOtherReason() {
        authenticateAs("bad@example.com");
        when(userProvisioningService.findByEmail("bad@example.com")).thenReturn(Optional.empty());
        DataIntegrityViolationException failure = new DataIntegrityViolationException("other constraint");
        when(userProvisioningService.provision("bad@example.com")).thenThrow(failure);

        assertThatThrownBy(() -> currentUserService.provisionCurrentUserIfMissing()).isSameAs(failure);
    }

    @Test
    void provisionRefusesToRunInsideTransaction() {
        authenticateAs("dev@example.com");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> currentUserService.provisionCurrentUserIfMissing())
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verifyNoInteractions(userProvisioningService);
    }

    private static void authenticateAs(String username) {
        authenticate(UsernamePasswordAuthenticationToken.authenticated(username, null, AuthorityUtils.NO_AUTHORITIES));
    }

    private static void authenticate(Authentication authentication) {
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}

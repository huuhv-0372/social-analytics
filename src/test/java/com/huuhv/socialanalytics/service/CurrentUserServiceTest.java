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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
        verifyNoInteractions(userProvisioningService);
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
    void provisionsUserOnFirstAccess() {
        User created = User.builder().id(2L).email("new@example.com").displayName("new@example.com").build();
        authenticateAs("new@example.com");
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());
        when(userProvisioningService.provision("new@example.com")).thenReturn(created);

        assertThat(currentUserService.getCurrentUser()).isSameAs(created);
        verify(userRepository, never()).save(any());
    }

    @Test
    void concurrentFirstAccessReadsUserCreatedByOtherRequest() {
        User createdByOther = User.builder().id(3L).email("race@example.com").displayName("race").build();
        authenticateAs("race@example.com");
        when(userRepository.findByEmail("race@example.com")).thenReturn(Optional.empty());
        when(userProvisioningService.provision("race@example.com"))
                .thenThrow(new DataIntegrityViolationException("uk_users_email"));
        when(userProvisioningService.findByEmail("race@example.com")).thenReturn(Optional.of(createdByOther));

        assertThat(currentUserService.getCurrentUser()).isSameAs(createdByOther);
    }

    @Test
    void rethrowsWhenProvisioningFailsForOtherReason() {
        authenticateAs("bad@example.com");
        when(userRepository.findByEmail("bad@example.com")).thenReturn(Optional.empty());
        DataIntegrityViolationException failure = new DataIntegrityViolationException("other constraint");
        when(userProvisioningService.provision("bad@example.com")).thenThrow(failure);
        when(userProvisioningService.findByEmail("bad@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> currentUserService.getCurrentUser()).isSameAs(failure);
    }

    private static void authenticateAs(String username) {
        authenticate(UsernamePasswordAuthenticationToken.authenticated(username, null, AuthorityUtils.NO_AUTHORITIES));
    }

    private static void authenticate(Authentication authentication) {
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}

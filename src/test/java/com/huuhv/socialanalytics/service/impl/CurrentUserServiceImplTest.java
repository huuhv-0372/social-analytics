package com.huuhv.socialanalytics.service.impl;

import com.huuhv.socialanalytics.entity.User;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CurrentUserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CurrentUserServiceImpl currentUserService;

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
        verify(userRepository, never()).save(any());
    }

    @Test
    void returnsExistingUser() {
        User existing = User.builder().id(1L).email("dev@example.com").displayName("Dev").build();
        authenticate(UsernamePasswordAuthenticationToken.authenticated("dev@example.com", null, AuthorityUtils.NO_AUTHORITIES));
        when(userRepository.findByEmail("dev@example.com")).thenReturn(Optional.of(existing));

        assertThat(currentUserService.getCurrentUser()).isSameAs(existing);
        verify(userRepository, never()).save(any());
    }

    @Test
    void provisionsUserOnFirstAccess() {
        authenticate(UsernamePasswordAuthenticationToken.authenticated("new@example.com", null, AuthorityUtils.NO_AUTHORITIES));
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User user = currentUserService.getCurrentUser();

        assertThat(user.getEmail()).isEqualTo("new@example.com");
        assertThat(user.getDisplayName()).isEqualTo("new@example.com");
    }

    private static void authenticate(Authentication authentication) {
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}

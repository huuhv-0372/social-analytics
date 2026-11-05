package com.huuhv.socialanalytics.service.impl;

import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.exception.AuthenticationRequiredException;
import com.huuhv.socialanalytics.repository.UserRepository;
import com.huuhv.socialanalytics.service.CurrentUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TẠM THỜI (tới Epic 3): map principal của HTTP Basic sang {@link User} theo email = username,
 * chưa có thì tạo mới (just-in-time). Task 3.4 thay bằng mapping từ OAuth2 principal + SocialAccount.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CurrentUserServiceImpl implements CurrentUserService {

    private static final int DISPLAY_NAME_MAX_LENGTH = 100;

    private final UserRepository userRepository;

    @Override
    @Transactional
    public User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new AuthenticationRequiredException();
        }
        String username = authentication.getName();
        return userRepository.findByEmail(username)
                .orElseGet(() -> createUser(username));
    }

    private User createUser(String username) {
        User user = userRepository.save(User.builder()
                .email(username)
                .displayName(username.length() > DISPLAY_NAME_MAX_LENGTH
                        ? username.substring(0, DISPLAY_NAME_MAX_LENGTH)
                        : username)
                .build());
        log.info("Provisioned user {} on first API access", user.getId());
        return user;
    }
}

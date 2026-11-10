package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Tạo/đọc user, mỗi lời gọi một transaction riêng ({@code REQUIRES_NEW}); tách khỏi {@link CurrentUserService} để đi
 * qua proxy. Chỉ gọi qua {@link CurrentUserService#provisionCurrentUserIfMissing()} — ngoài mọi transaction khác,
 * để mỗi request chỉ giữ 1 connection tại một thời điểm.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserProvisioningService {

    private static final int DISPLAY_NAME_MAX_LENGTH = 100;

    private final UserRepository userRepository;

    /**
     * @throws org.springframework.dao.DataIntegrityViolationException khi request khác vừa tạo user cùng email
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public User provision(String email) {
        User user = userRepository.saveAndFlush(User.builder()
                .email(email)
                .displayName(email.length() > DISPLAY_NAME_MAX_LENGTH
                        ? email.substring(0, DISPLAY_NAME_MAX_LENGTH)
                        : email)
                .build());
        log.info("Provisioned user {} on first API access", user.getId());
        return user;
    }

    /** Đọc trong transaction mới để thấy bản ghi vừa được transaction khác commit. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }
}

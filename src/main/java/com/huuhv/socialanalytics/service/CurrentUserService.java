package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.exception.AuthenticationRequiredException;
import com.huuhv.socialanalytics.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Xác định user đang đăng nhập từ SecurityContext. Không bao giờ tin userId do client gửi lên (OWASP A01).
 * <p>
 * TẠM THỜI (tới Epic 3): map principal của HTTP Basic sang {@link User} theo email = username,
 * chưa có thì tạo mới (just-in-time). Task 3.4 thay bằng mapping từ OAuth2 principal + SocialAccount.
 */
@Service
@RequiredArgsConstructor
public class CurrentUserService {

    private final UserRepository userRepository;
    private final UserProvisioningService userProvisioningService;

    /**
     * Entity trả về có thể không thuộc persistence context hiện tại (khi vừa được tạo) — chỉ dùng id hoặc làm tham chiếu.
     *
     * @throws AuthenticationRequiredException khi request chưa đăng nhập
     */
    @Transactional(readOnly = true)
    public User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new AuthenticationRequiredException();
        }
        String username = authentication.getName();
        return userRepository.findByEmail(username)
                .orElseGet(() -> provision(username));
    }

    private User provision(String username) {
        try {
            return userProvisioningService.provision(username);
        } catch (DataIntegrityViolationException e) {
            // Request song song vừa tạo user cùng email (uk_users_email) → đọc lại bản ghi đã commit
            return userProvisioningService.findByEmail(username).orElseThrow(() -> e);
        }
    }
}

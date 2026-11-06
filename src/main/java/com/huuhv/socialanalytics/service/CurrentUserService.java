package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.exception.AuthenticationRequiredException;
import com.huuhv.socialanalytics.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;

/**
 * Xác định user đang đăng nhập từ SecurityContext. Không bao giờ tin userId do client gửi lên (OWASP A01).
 * <p>
 * TẠM THỜI (tới Epic 3): map principal của HTTP Basic sang {@link User} theo email = username.
 * User được tạo just-in-time bởi {@link #provisionCurrentUserIfMissing()} (gọi từ interceptor của {@code /api/**},
 * trước mọi transaction); {@link #getCurrentUser()} chỉ đọc. Task 3.4 thay bằng mapping từ OAuth2 principal + SocialAccount.
 */
@Service
@RequiredArgsConstructor
public class CurrentUserService {

    private final UserRepository userRepository;
    private final UserProvisioningService userProvisioningService;

    /**
     * @throws AuthenticationRequiredException khi request chưa đăng nhập hoặc user chưa được tạo (fail closed)
     */
    @Transactional(readOnly = true)
    public User getCurrentUser() {
        String username = currentUsername().orElseThrow(AuthenticationRequiredException::new);
        return userRepository.findByEmail(username).orElseThrow(AuthenticationRequiredException::new);
    }

    /**
     * Tạo user cho principal hiện tại nếu chưa có; request ẩn danh thì bỏ qua (Service sẽ trả 401).
     * <p>
     * Phải gọi <b>ngoài</b> transaction: {@link UserProvisioningService} dùng {@code REQUIRES_NEW}, nếu chạy lồng trong
     * transaction khác thì mỗi request giữ 2 connection cùng lúc và pool có thể deadlock khi tải cao.
     */
    public void provisionCurrentUserIfMissing() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("User provisioning must run outside of a transaction");
        }
        currentUsername()
                .filter(username -> userProvisioningService.findByEmail(username).isEmpty())
                .ifPresent(this::provision);
    }

    private void provision(String username) {
        try {
            userProvisioningService.provision(username);
        } catch (DataIntegrityViolationException e) {
            // Request song song vừa tạo user cùng email (uk_users_email) → bản ghi đã tồn tại, coi như thành công
            if (userProvisioningService.findByEmail(username).isEmpty()) {
                throw e;
            }
        }
    }

    private static Optional<String> currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                // TẠM THỜI tới Task 3.4: tên principal OAuth2 là id phía provider (vd id Facebook), không phải email →
                // không map/tạo User theo tên này (tránh tạo bản ghi sai); fail closed với 401 cho tới khi có SocialAccount
                || authentication instanceof OAuth2AuthenticationToken) {
            return Optional.empty();
        }
        return Optional.of(authentication.getName());
    }
}

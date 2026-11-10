package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.enums.Provider;
import com.huuhv.socialanalytics.exception.AuthenticationRequiredException;
import com.huuhv.socialanalytics.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Service;

/**
 * Xác định user đang đăng nhập từ SecurityContext. Không bao giờ tin userId do client gửi lên (OWASP A01).
 * <ul>
 *     <li>Principal là {@link AppOAuth2User} (tạo lúc đăng nhập bởi {@link CustomOAuth2UserService}) → kiểm tra user
 *     theo id lưu trong session còn tồn tại (1 query {@code existsById} theo khoá chính, không mở transaction) rồi
 *     trả reference. User đã bị xoá / DB bị reset → 401: session cũ không được thao tác như owner (id có thể đã được
 *     cấp cho user khác). Caller chỉ dùng {@code getId()} hoặc gán làm khoá ngoại — đọc field khác của proxy ngoài
 *     transaction sẽ lỗi lazy-load.</li>
 *     <li>Principal OAuth2 khác (session tạo trước khi có {@link AppOAuth2User}, test) → tra
 *     {@code social_accounts} theo {@code registrationId} + id phía provider.</li>
 * </ul>
 * Kiểu xác thực khác, provider lạ hoặc tài khoản chưa được lưu đều bị từ chối (fail closed).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CurrentUserService {

    private final UserRepository userRepository;

    /**
     * @throws AuthenticationRequiredException khi request chưa đăng nhập OAuth2 hoặc tài khoản không có trong DB
     */
    public User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof OAuth2AuthenticationToken token) || !token.isAuthenticated()) {
            throw new AuthenticationRequiredException();
        }
        if (token.getPrincipal() instanceof AppOAuth2User principal) {
            Long userId = principal.getUserId();
            if (!userRepository.existsById(userId)) {
                log.warn("Authenticated session refers to missing user {}, rejecting request", userId);
                throw new AuthenticationRequiredException();
            }
            return userRepository.getReferenceById(userId);
        }
        Provider provider = Provider.fromRegistrationId(token.getAuthorizedClientRegistrationId())
                .orElseThrow(AuthenticationRequiredException::new);
        return userRepository.findBySocialAccount(provider, token.getName())
                .orElseThrow(AuthenticationRequiredException::new);
    }
}

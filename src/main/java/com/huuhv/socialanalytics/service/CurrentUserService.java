package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.enums.Provider;
import com.huuhv.socialanalytics.exception.AuthenticationRequiredException;
import com.huuhv.socialanalytics.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Xác định user đang đăng nhập từ SecurityContext. Không bao giờ tin userId do client gửi lên (OWASP A01).
 * <p>
 * Principal OAuth2 ({@code registrationId} + id phía provider) được map sang {@link User} qua {@code social_accounts}
 * — bản ghi do {@link SocialAccountService} tạo lúc đăng nhập. Kiểu xác thực khác, provider lạ hoặc tài khoản chưa
 * được lưu đều bị từ chối (fail closed).
 */
@Service
@RequiredArgsConstructor
public class CurrentUserService {

    private final UserRepository userRepository;

    /**
     * @throws AuthenticationRequiredException khi request chưa đăng nhập OAuth2 hoặc tài khoản không có trong DB
     */
    @Transactional(readOnly = true)
    public User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof OAuth2AuthenticationToken token) || !token.isAuthenticated()) {
            throw new AuthenticationRequiredException();
        }
        Provider provider = Provider.fromRegistrationId(token.getAuthorizedClientRegistrationId())
                .orElseThrow(AuthenticationRequiredException::new);
        return userRepository.findBySocialAccount(provider, token.getName())
                .orElseThrow(AuthenticationRequiredException::new);
    }
}

package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.enums.Provider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

/**
 * Bước "user info" của đăng nhập OAuth2 (sau khi đổi authorization code lấy access token):
 * <ol>
 *     <li>Gọi user-info-uri của provider qua {@code delegate} (bean ở {@code config/OAuth2ClientConfig}, đã gắn
 *     {@link OAuth2UserAttributes} để chuẩn hoá thuộc tính theo provider).</li>
 *     <li>Upsert {@code User} + {@code SocialAccount} kèm access token đã mã hoá ({@link SocialAccountService}).</li>
 *     <li>Trả {@link AppOAuth2User}: thuộc tính provider + id nội bộ của user (dùng bởi {@link CurrentUserService}).</li>
 * </ol>
 * Lỗi lưu DB từ chối đăng nhập (fail closed) → {@code /login?error}, chi tiết chỉ ghi log. Không bao giờ log access
 * token.
 * <p>
 * Không {@code @Transactional}: mỗi lần gọi {@link SocialAccountService} là một transaction riêng, để thử lại được
 * khi đăng nhập song song cùng tài khoản vi phạm unique key hoặc bị InnoDB chọn làm nạn nhân deadlock.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    static final String INVALID_USER_INFO_RESPONSE = OAuth2UserAttributes.INVALID_USER_INFO_RESPONSE;

    private final DefaultOAuth2UserService delegate;
    private final SocialAccountService socialAccountService;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        String registrationId = userRequest.getClientRegistration().getRegistrationId();
        Provider provider = Provider.fromRegistrationId(registrationId)
                .orElseThrow(() -> new OAuth2AuthenticationException(new OAuth2Error(
                        OAuth2ErrorCodes.INVALID_REQUEST, "Unsupported OAuth2 provider", null)));
        OAuth2User oauth2User = delegate.loadUser(userRequest);
        User user = saveLogin(provider, oauth2User, userRequest);
        // Gắn id nội bộ vào principal (lưu trong session) → CurrentUserService không phải query lại mỗi request
        String nameAttributeKey = userRequest.getClientRegistration().getProviderDetails()
                .getUserInfoEndpoint().getUserNameAttributeName();
        return new AppOAuth2User(oauth2User.getAuthorities(), oauth2User.getAttributes(), nameAttributeKey,
                user.getId());
    }

    private User saveLogin(Provider provider, OAuth2User oauth2User, OAuth2UserRequest userRequest) {
        try {
            try {
                return socialAccountService.recordLogin(provider, oauth2User, userRequest.getAccessToken());
            } catch (DataIntegrityViolationException | PessimisticLockingFailureException e) {
                // Lần đăng nhập song song (vd double-click, nhiều tab) vừa tạo cùng tài khoản (vi phạm unique) hoặc
                // tranh chấp khoá unique key (deadlock / lock wait timeout) → thử lại 1 lần sẽ cập nhật bản ghi đó.
                // Không retry mọi TransientDataAccessException: mất kết nối / timeout thử lại ngay không giúp được gì.
                log.debug("Concurrent {} login detected ({}), retrying", provider, e.getClass().getSimpleName());
                return socialAccountService.recordLogin(provider, oauth2User, userRequest.getAccessToken());
            }
        } catch (IllegalArgumentException e) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error(INVALID_USER_INFO_RESPONSE, "Invalid user id in user info response", null));
        } catch (DataAccessException e) {
            log.error("Failed to save {} account on login", provider, e);
            throw new OAuth2AuthenticationException(
                    new OAuth2Error(OAuth2ErrorCodes.SERVER_ERROR, "Failed to save account", null), e);
        }
    }
}

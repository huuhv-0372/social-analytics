package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.enums.Provider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.convert.converter.Converter;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bước "user info" của đăng nhập OAuth2 (sau khi đổi authorization code lấy access token):
 * <ol>
 *     <li>Gọi user-info-uri của provider và chuẩn hoá thuộc tính:
 *     Facebook giữ nguyên ({@code id}, {@code name}, {@code email}); X ({@code GET /2/users/me}) bọc thông tin trong
 *     {@code data} → bóc ra để có {@code id}, {@code name}, {@code username} ở cấp trên cùng.</li>
 *     <li>Upsert {@code User} + {@code SocialAccount} kèm access token đã mã hoá ({@link SocialAccountService}).</li>
 * </ol>
 * Response của provider là dữ liệu không tin cậy (OWASP A08): thiếu {@code id} thì từ chối đăng nhập. Lỗi lưu DB cũng
 * từ chối đăng nhập (fail closed) → {@code /login?error}, chi tiết chỉ ghi log. Không bao giờ log access token.
 * <p>
 * Không {@code @Transactional}: mỗi lần gọi {@link SocialAccountService} là một transaction riêng, để thử lại được
 * khi đăng nhập song song cùng tài khoản vi phạm unique key.
 */
@Slf4j
@Service
public class CustomOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    static final String INVALID_USER_INFO_RESPONSE = "invalid_user_info_response";

    private final DefaultOAuth2UserService delegate;
    private final SocialAccountService socialAccountService;

    @Autowired
    public CustomOAuth2UserService(SocialAccountService socialAccountService) {
        this(new DefaultOAuth2UserService(), socialAccountService);
    }

    /** Cho test thay {@code RestOperations} của delegate (không gọi provider thật). */
    CustomOAuth2UserService(DefaultOAuth2UserService delegate, SocialAccountService socialAccountService) {
        this.delegate = delegate;
        this.delegate.setAttributesConverter(CustomOAuth2UserService::attributesConverter);
        this.socialAccountService = socialAccountService;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        String registrationId = userRequest.getClientRegistration().getRegistrationId();
        Provider provider = Provider.fromRegistrationId(registrationId)
                .orElseThrow(() -> new OAuth2AuthenticationException(new OAuth2Error(
                        OAuth2ErrorCodes.INVALID_REQUEST, "Unsupported OAuth2 provider", null)));
        OAuth2User oauth2User = delegate.loadUser(userRequest);
        saveLogin(provider, oauth2User, userRequest);
        return oauth2User;
    }

    private void saveLogin(Provider provider, OAuth2User oauth2User, OAuth2UserRequest userRequest) {
        try {
            try {
                socialAccountService.recordLogin(provider, oauth2User, userRequest.getAccessToken());
            } catch (DataIntegrityViolationException e) {
                // Lần đăng nhập song song (vd double-click) vừa tạo cùng tài khoản → thử lại 1 lần sẽ cập nhật bản ghi đó
                log.debug("Concurrent {} login detected, retrying", provider);
                socialAccountService.recordLogin(provider, oauth2User, userRequest.getAccessToken());
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

    private static Converter<Map<String, Object>, Map<String, Object>> attributesConverter(OAuth2UserRequest request) {
        if (Provider.TWITTER.registrationId().equals(request.getClientRegistration().getRegistrationId())) {
            return CustomOAuth2UserService::unwrapTwitterData;
        }
        return attributes -> attributes;
    }

    private static Map<String, Object> unwrapTwitterData(Map<String, Object> attributes) {
        if (attributes.get("data") instanceof Map<?, ?> data
                && data.get("id") instanceof String id
                && !id.isBlank()) {
            Map<String, Object> unwrapped = new LinkedHashMap<>();
            data.forEach((key, value) -> unwrapped.put(String.valueOf(key), value));
            return unwrapped;
        }
        // Không đưa nội dung response vào thông báo lỗi (có thể chứa dữ liệu cá nhân)
        throw new OAuth2AuthenticationException(
                new OAuth2Error(INVALID_USER_INFO_RESPONSE, "Missing user id in X user info response", null));
    }
}

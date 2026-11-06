package com.huuhv.socialanalytics.service;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tải user info sau khi đổi authorization code lấy access token (gọi user-info-uri của provider).
 * <ul>
 *     <li>Facebook: giữ nguyên thuộc tính ({@code id}, {@code name}, {@code email}).</li>
 *     <li>X (Twitter): {@code GET /2/users/me} bọc thông tin trong {@code data} → bóc ra để có {@code id}, {@code name},
 *     {@code username} ở cấp trên cùng (user-name-attribute là {@code id}).</li>
 * </ul>
 * Response của provider là dữ liệu không tin cậy (OWASP A08): thiếu {@code id} thì từ chối đăng nhập.
 * Task 3.4 bổ sung upsert {@code User} + {@code SocialAccount} tại đây.
 */
@Service
public class CustomOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    static final String TWITTER_REGISTRATION_ID = "twitter";
    static final String INVALID_USER_INFO_RESPONSE = "invalid_user_info_response";

    private final DefaultOAuth2UserService delegate;

    public CustomOAuth2UserService() {
        this(new DefaultOAuth2UserService());
    }

    /** Cho test thay {@code RestOperations} của delegate (không gọi provider thật). */
    CustomOAuth2UserService(DefaultOAuth2UserService delegate) {
        this.delegate = delegate;
        this.delegate.setAttributesConverter(CustomOAuth2UserService::attributesConverter);
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        return delegate.loadUser(userRequest);
    }

    private static Converter<Map<String, Object>, Map<String, Object>> attributesConverter(OAuth2UserRequest request) {
        if (TWITTER_REGISTRATION_ID.equals(request.getClientRegistration().getRegistrationId())) {
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

package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.enums.Provider;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Chuẩn hoá response user-info-uri theo provider, gắn vào {@code DefaultOAuth2UserService} qua
 * {@code setAttributesConverter(OAuth2UserAttributes::converterFor)} (bean ở {@code config/OAuth2ClientConfig}):
 * Facebook giữ nguyên ({@code id}, {@code name}, {@code email}); X ({@code GET /2/users/me}) bọc thông tin trong
 * {@code data} → bóc ra để có {@code id}, {@code name}, {@code username} ở cấp trên cùng.
 * <p>
 * Response của provider là dữ liệu không tin cậy (OWASP A08): thiếu {@code id} (mọi provider) thì từ chối đăng nhập
 * bằng {@link OAuth2AuthenticationException} → {@code /login?error}. Nếu để lọt, {@code DefaultOAuth2User} ném
 * {@code IllegalArgumentException} và callback thành lỗi 500.
 */
public final class OAuth2UserAttributes {

    public static final String INVALID_USER_INFO_RESPONSE = "invalid_user_info_response";

    private static final String ID = "id";

    private OAuth2UserAttributes() {
    }

    public static Converter<Map<String, Object>, Map<String, Object>> converterFor(OAuth2UserRequest request) {
        if (Provider.TWITTER.registrationId().equals(request.getClientRegistration().getRegistrationId())) {
            return OAuth2UserAttributes::unwrapTwitterData;
        }
        return OAuth2UserAttributes::requireId;
    }

    private static Map<String, Object> unwrapTwitterData(Map<String, Object> attributes) {
        if (!(attributes.get("data") instanceof Map<?, ?> data)) {
            throw missingId();
        }
        Map<String, Object> unwrapped = new LinkedHashMap<>();
        data.forEach((key, value) -> unwrapped.put(String.valueOf(key), value));
        return requireId(unwrapped);
    }

    private static Map<String, Object> requireId(Map<String, Object> attributes) {
        if (attributes.get(ID) instanceof String id && !id.isBlank()) {
            return attributes;
        }
        throw missingId();
    }

    /** Không đưa nội dung response vào thông báo lỗi (có thể chứa dữ liệu cá nhân). */
    private static OAuth2AuthenticationException missingId() {
        return new OAuth2AuthenticationException(
                new OAuth2Error(INVALID_USER_INFO_RESPONSE, "Missing user id in user info response", null));
    }
}

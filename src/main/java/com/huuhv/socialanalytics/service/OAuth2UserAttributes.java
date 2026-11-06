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
 * Response của provider là dữ liệu không tin cậy (OWASP A08): thiếu {@code id} thì từ chối đăng nhập.
 */
public final class OAuth2UserAttributes {

    public static final String INVALID_USER_INFO_RESPONSE = "invalid_user_info_response";

    private OAuth2UserAttributes() {
    }

    public static Converter<Map<String, Object>, Map<String, Object>> converterFor(OAuth2UserRequest request) {
        if (Provider.TWITTER.registrationId().equals(request.getClientRegistration().getRegistrationId())) {
            return OAuth2UserAttributes::unwrapTwitterData;
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

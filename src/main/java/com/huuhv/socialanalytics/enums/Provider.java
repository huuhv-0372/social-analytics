package com.huuhv.socialanalytics.enums;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Nền tảng mạng xã hội nguồn của bài viết / tài khoản, kèm whitelist domain hợp lệ cho URL bài viết (chống SSRF)
 * và id đăng ký OAuth2 tương ứng ({@code spring.security.oauth2.client.registration.<registrationId>}).
 */
public enum Provider {
    FACEBOOK("facebook", Set.of("facebook.com")),
    TWITTER("twitter", Set.of("x.com", "twitter.com"));

    private final String registrationId;
    private final Set<String> allowedDomains;

    Provider(String registrationId, Set<String> allowedDomains) {
        this.registrationId = registrationId;
        this.allowedDomains = allowedDomains;
    }

    public String registrationId() {
        return registrationId;
    }

    /** Provider ứng với id đăng ký OAuth2; id lạ → rỗng (caller từ chối, fail closed). */
    public static Optional<Provider> fromRegistrationId(String registrationId) {
        return Arrays.stream(values())
                .filter(provider -> provider.registrationId.equals(registrationId))
                .findFirst();
    }

    /** Host khớp đúng domain hoặc là subdomain của nó (vd {@code www.facebook.com}, {@code m.facebook.com}). */
    public boolean isAllowedHost(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return allowedDomains.stream()
                .anyMatch(domain -> normalized.equals(domain) || normalized.endsWith("." + domain));
    }
}

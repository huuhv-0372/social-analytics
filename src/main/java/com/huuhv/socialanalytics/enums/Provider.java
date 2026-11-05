package com.huuhv.socialanalytics.enums;

import java.util.Locale;
import java.util.Set;

/**
 * Nền tảng mạng xã hội nguồn của bài viết / tài khoản, kèm whitelist domain hợp lệ cho URL bài viết (chống SSRF).
 */
public enum Provider {
    FACEBOOK(Set.of("facebook.com")),
    TWITTER(Set.of("x.com", "twitter.com"));

    private final Set<String> allowedDomains;

    Provider(Set<String> allowedDomains) {
        this.allowedDomains = allowedDomains;
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

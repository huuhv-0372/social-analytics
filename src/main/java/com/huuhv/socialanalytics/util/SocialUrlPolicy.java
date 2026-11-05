package com.huuhv.socialanalytics.util;

import com.huuhv.socialanalytics.enums.Provider;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * URL bài viết chỉ hợp lệ khi dùng https và host thuộc whitelist domain của provider (chống SSRF — OWASP A06).
 */
public final class SocialUrlPolicy {

    private SocialUrlPolicy() {
    }

    public static boolean isAllowed(Provider provider, String url) {
        if (provider == null || url == null) {
            return false;
        }
        try {
            URI uri = new URI(url);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getUserInfo() == null
                    && provider.isAllowedHost(uri.getHost());
        } catch (URISyntaxException e) {
            return false;
        }
    }
}

package com.huuhv.socialanalytics.util;

import com.huuhv.socialanalytics.enums.Provider;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * URL bài viết chỉ hợp lệ khi dùng https, port mặc định (443) và host thuộc whitelist domain của provider
 * (chống SSRF — OWASP A06: crawler không bị dẫn tới port tuỳ ý trên host được phép).
 */
public final class SocialUrlPolicy {

    /** Thông điệp lỗi dùng chung cho mọi nơi kiểm tra URL (REST API, import Excel). */
    public static final String VIOLATION_MESSAGE = "must be an https link on a domain of the selected provider";

    private static final int UNSPECIFIED_PORT = -1;
    private static final int HTTPS_PORT = 443;

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
                    && (uri.getPort() == UNSPECIFIED_PORT || uri.getPort() == HTTPS_PORT)
                    && provider.isAllowedHost(uri.getHost());
        } catch (URISyntaxException e) {
            return false;
        }
    }
}

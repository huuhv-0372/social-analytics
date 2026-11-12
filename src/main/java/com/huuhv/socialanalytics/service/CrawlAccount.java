package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.entity.SocialAccount;
import com.huuhv.socialanalytics.enums.Provider;

import java.time.LocalDateTime;

/**
 * Dữ liệu 1 tài khoản mà job crawl metrics cần, tách khỏi Entity để dùng ngoài transaction. Chứa access token bản rõ
 * → {@link #toString()} che token (record mặc định in mọi component, dễ lọt vào log).
 *
 * @param tokenExpiresAt hạn token, giờ UTC; {@code null} = provider không báo hạn
 */
public record CrawlAccount(Long id, Long userId, Provider provider, String accessToken, LocalDateTime tokenExpiresAt) {

    static CrawlAccount from(SocialAccount account) {
        // getUser().getId() đọc id từ proxy lazy, không query bảng users
        return new CrawlAccount(account.getId(), account.getUser().getId(), account.getProvider(),
                account.getAccessToken(), account.getTokenExpiresAt());
    }

    /**
     * Token còn dùng được tại {@code nowUtc} (OWASP A07): có token (không giải mã được → {@code null}) và chưa hết hạn.
     */
    public boolean hasUsableToken(LocalDateTime nowUtc) {
        return accessToken != null && !accessToken.isBlank()
                && (tokenExpiresAt == null || tokenExpiresAt.isAfter(nowUtc));
    }

    @Override
    public String toString() {
        return "CrawlAccount[id=" + id + ", userId=" + userId + ", provider=" + provider
                + ", accessToken=******, tokenExpiresAt=" + tokenExpiresAt + "]";
    }
}

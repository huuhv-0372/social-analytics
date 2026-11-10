package com.huuhv.socialanalytics.dto.response;

import com.huuhv.socialanalytics.entity.Post;
import com.huuhv.socialanalytics.enums.Provider;
import com.huuhv.socialanalytics.util.UtcDateTimes;

import java.time.Instant;

/**
 * Mốc thời gian trả dạng {@link Instant} (ISO-8601 UTC, vd {@code 2026-11-07T01:00:00Z}); client tự đổi sang giờ
 * địa phương khi hiển thị.
 */
public record PostResponse(
        Long id,
        Provider provider,
        String externalId,
        String url,
        String content,
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt) {

    public static PostResponse from(Post post) {
        return new PostResponse(
                post.getId(),
                post.getProvider(),
                post.getExternalId(),
                post.getUrl(),
                post.getContent(),
                UtcDateTimes.toInstant(post.getPublishedAt()),
                UtcDateTimes.toInstant(post.getCreatedAt()),
                UtcDateTimes.toInstant(post.getUpdatedAt()));
    }
}

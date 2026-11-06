package com.huuhv.socialanalytics.dto.response;

import com.huuhv.socialanalytics.entity.Post;
import com.huuhv.socialanalytics.enums.Provider;

import java.time.LocalDateTime;

public record PostResponse(
        Long id,
        Provider provider,
        String externalId,
        String url,
        String content,
        LocalDateTime publishedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static PostResponse from(Post post) {
        return new PostResponse(
                post.getId(),
                post.getProvider(),
                post.getExternalId(),
                post.getUrl(),
                post.getContent(),
                post.getPublishedAt(),
                post.getCreatedAt(),
                post.getUpdatedAt());
    }
}

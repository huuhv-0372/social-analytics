package com.huuhv.socialanalytics.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.LocalDateTime;

/**
 * Ghi nhận 1 snapshot metric thủ công cho bài viết của user hiện tại.
 */
public record SocialMetricRequest(
        @NotNull @Positive Long postId,
        @NotNull @PositiveOrZero Long likes,
        @NotNull @PositiveOrZero Long shares,
        @NotNull @PositiveOrZero Long comments,
        @NotNull @PositiveOrZero Long followers,
        @NotNull @PastOrPresent LocalDateTime collectedAt) {
}

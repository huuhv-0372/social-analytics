package com.huuhv.socialanalytics.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;

/**
 * Ghi nhận 1 snapshot metric thủ công cho bài viết của user hiện tại.
 * {@code collectedAt} là mốc thời gian tuyệt đối: ISO-8601 có {@code Z} hoặc offset; thiếu múi giờ → 400.
 */
public record SocialMetricRequest(
        @NotNull @Positive Long postId,
        @NotNull @PositiveOrZero Long likes,
        @NotNull @PositiveOrZero Long shares,
        @NotNull @PositiveOrZero Long comments,
        @NotNull @PositiveOrZero Long followers,
        @NotNull @PastOrPresent Instant collectedAt) {
}

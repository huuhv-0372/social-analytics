package com.huuhv.socialanalytics.dto.response;

import com.huuhv.socialanalytics.entity.SocialMetric;
import com.huuhv.socialanalytics.util.UtcDateTimes;

import java.time.Instant;

/**
 * {@code collectedAt} trả dạng {@link Instant} (ISO-8601 UTC).
 */
public record SocialMetricResponse(
        Long id,
        Long postId,
        long likes,
        long shares,
        long comments,
        long followers,
        Instant collectedAt) {

    public static SocialMetricResponse from(SocialMetric metric) {
        // getPost().getId() không khởi tạo LAZY proxy
        return new SocialMetricResponse(
                metric.getId(),
                metric.getPost().getId(),
                metric.getLikes(),
                metric.getShares(),
                metric.getComments(),
                metric.getFollowers(),
                UtcDateTimes.toInstant(metric.getCollectedAt()));
    }
}

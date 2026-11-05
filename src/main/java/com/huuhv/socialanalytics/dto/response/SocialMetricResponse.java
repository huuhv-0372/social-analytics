package com.huuhv.socialanalytics.dto.response;

import com.huuhv.socialanalytics.entity.SocialMetric;

import java.time.LocalDateTime;

public record SocialMetricResponse(
        Long id,
        Long postId,
        long likes,
        long shares,
        long comments,
        long followers,
        LocalDateTime collectedAt) {

    public static SocialMetricResponse from(SocialMetric metric) {
        // getPost().getId() không khởi tạo LAZY proxy
        return new SocialMetricResponse(
                metric.getId(),
                metric.getPost().getId(),
                metric.getLikes(),
                metric.getShares(),
                metric.getComments(),
                metric.getFollowers(),
                metric.getCollectedAt());
    }
}

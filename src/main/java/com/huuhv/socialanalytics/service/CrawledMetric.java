package com.huuhv.socialanalytics.service;

/**
 * Số liệu 1 bài đã được job crawl và kiểm tra hợp lệ, chờ lưu thành snapshot {@code social_metrics}.
 *
 * @param postId id nội bộ của bài viết (không phải id phía provider)
 */
public record CrawledMetric(Long postId, long likes, long shares, long comments, long followers) {
}

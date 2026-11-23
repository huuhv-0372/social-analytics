package com.huuhv.socialanalytics.dto.response;

/**
 * Projection tối thiểu của bài viết cho job crawl metrics: id nội bộ (khoá ngoại snapshot) + id phía provider
 * (tham số gọi API). Không load Entity.
 */
public record PostRef(Long id, String externalId) {
}

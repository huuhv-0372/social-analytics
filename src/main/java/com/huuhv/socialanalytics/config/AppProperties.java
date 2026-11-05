package com.huuhv.socialanalytics.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Cấu hình riêng của ứng dụng, prefix {@code app.*}. Sai giá trị → app không khởi động (fail fast).
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(@Valid @DefaultValue Pagination pagination) {

    /**
     * Giới hạn phân trang cho API danh sách (OWASP A06 — giới hạn tài nguyên).
     */
    public record Pagination(
            @Positive @DefaultValue("20") int defaultSize,
            @Positive @Max(1000) @DefaultValue("100") int maxSize) {

        @AssertTrue(message = "app.pagination.default-size must not exceed app.pagination.max-size")
        public boolean isDefaultSizeWithinMax() {
            return defaultSize <= maxSize;
        }
    }
}

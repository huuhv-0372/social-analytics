package com.huuhv.socialanalytics.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Cấu hình riêng của ứng dụng, prefix {@code app.*}. Sai giá trị → app không khởi động (fail fast).
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @Valid @DefaultValue Pagination pagination,
        // "import" là từ khoá Java → đặt tên khác cho component, bind theo key app.import.*
        @Valid @DefaultValue @Name("import") ImportSettings importSettings) {

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

    /**
     * Giới hạn import Excel (OWASP A06). Dung lượng file nén giới hạn riêng qua {@code spring.servlet.multipart.*}.
     *
     * @param maxUncompressedSize tổng dung lượng sau giải nén của file .xlsx (chặn file nén cao làm tràn heap)
     */
    public record ImportSettings(
            @Positive @Max(10000) @DefaultValue("1000") int maxRows,
            @NotNull @DefaultValue("20MB") DataSize maxUncompressedSize) {

        @AssertTrue(message = "app.import.max-uncompressed-size must be positive")
        public boolean isMaxUncompressedSizePositive() {
            return maxUncompressedSize == null || maxUncompressedSize.toBytes() > 0;
        }
    }
}

package com.huuhv.socialanalytics.config.properties;

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

import java.time.Duration;

/**
 * Cấu hình riêng của ứng dụng, prefix {@code app.*}. Sai giá trị → app không khởi động (fail fast).
 * Giới hạn phân trang dùng {@code spring.data.web.pageable.*} của Boot, không khai ở đây.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        // "import" là từ khoá Java → đặt tên khác cho component, bind theo key app.import.*
        @Valid @DefaultValue @Name("import") ImportSettings importSettings,
        @Valid @DefaultValue @Name("export") ExportSettings exportSettings) {

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

    /**
     * Giới hạn export Excel (OWASP A06): báo cáo vượt {@code maxRows} dòng bị từ chối thay vì dựng file khổng lồ.
     *
     * @param minInterval khoảng cách tối thiểu giữa 2 lần export của cùng 1 user (vi phạm → 429); {@code 0} = tắt
     */
    public record ExportSettings(
            @Positive @Max(100000) @DefaultValue("10000") int maxRows,
            @NotNull @DefaultValue("10s") Duration minInterval) {

        @AssertTrue(message = "app.export.min-interval must not be negative")
        public boolean isMinIntervalNotNegative() {
            return minInterval == null || !minInterval.isNegative();
        }
    }
}

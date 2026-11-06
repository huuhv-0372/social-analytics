package com.huuhv.socialanalytics.config;

import com.huuhv.socialanalytics.util.AesGcmCipher;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Base64;

/**
 * Cấu hình riêng của ứng dụng, prefix {@code app.*}. Sai giá trị → app không khởi động (fail fast).
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @Valid @DefaultValue Pagination pagination,
        // "import" là từ khoá Java → đặt tên khác cho component, bind theo key app.import.*
        @Valid @DefaultValue @Name("import") ImportSettings importSettings,
        @Valid @DefaultValue @Name("export") ExportSettings exportSettings,
        @Valid @DefaultValue TokenEncryption tokenEncryption) {

    /**
     * Key AES-256 mã hoá access token lưu DB (OWASP A04): Base64 của đúng 32 byte, lấy từ biến môi trường
     * {@code TOKEN_ENCRYPTION_KEY} (sinh bằng {@code openssl rand -base64 32}). Thiếu hoặc sai → app không khởi động.
     * Đổi key làm token đã lưu không giải mã được — user phải đăng nhập lại.
     */
    public record TokenEncryption(
            @NotBlank(message = "app.token-encryption.key (TOKEN_ENCRYPTION_KEY) must be set") String key) {

        @AssertTrue(message = "app.token-encryption.key must be Base64 of exactly 32 bytes (AES-256)")
        public boolean isKeyValid() {
            return key == null || key.isBlank() || decode(key) != null;
        }

        public byte[] keyBytes() {
            byte[] bytes = decode(key);
            if (bytes == null) {
                throw new IllegalStateException("app.token-encryption.key is invalid");
            }
            return bytes;
        }

        private static byte[] decode(String value) {
            try {
                byte[] bytes = Base64.getDecoder().decode(value.strip());
                return bytes.length == AesGcmCipher.KEY_LENGTH_BYTES ? bytes : null;
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        /** Không để key lọt ra log / thông báo lỗi bind cấu hình (toString mặc định của record in mọi component). */
        @Override
        public String toString() {
            return "TokenEncryption[key=******]";
        }
    }

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

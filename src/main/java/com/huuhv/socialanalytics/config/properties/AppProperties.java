package com.huuhv.socialanalytics.config.properties;

import com.huuhv.socialanalytics.util.AesGcmCipher;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * Cấu hình riêng của ứng dụng, prefix {@code app.*}. Sai giá trị → app không khởi động (fail fast).
 * Giới hạn phân trang dùng {@code spring.data.web.pageable.*} của Boot, không khai ở đây.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        // "import" là từ khoá Java → đặt tên khác cho component, bind theo key app.import.*
        @Valid @DefaultValue @Name("import") ImportSettings importSettings,
        @Valid @DefaultValue @Name("export") ExportSettings exportSettings,
        @Valid @DefaultValue TokenEncryption tokenEncryption,
        @Valid @DefaultValue OAuth2Client oauth2,
        @Valid @DefaultValue AsyncSettings async,
        @Valid @DefaultValue Jobs jobs,
        @Valid @DefaultValue JmsSettings jms) {

    /**
     * Timeout khi gọi provider OAuth2 (đổi code lấy token, user info) trong lúc login callback (OWASP A10): provider
     * treo không được giữ thread Tomcat vô thời hạn. Hết giờ → {@code /login?error}.
     */
    public record OAuth2Client(
            @NotNull @DefaultValue("5s") Duration connectTimeout,
            @NotNull @DefaultValue("10s") Duration readTimeout) {

        @AssertTrue(message = "app.oauth2.connect-timeout and app.oauth2.read-timeout must be positive")
        public boolean isTimeoutsPositive() {
            return isPositive(connectTimeout) && isPositive(readTimeout);
        }

        private static boolean isPositive(Duration value) {
            // null đã báo bởi @NotNull
            return value == null || (!value.isNegative() && !value.isZero());
        }
    }

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

    /**
     * Thread pool cho tác vụ chạy nền ({@code app.async.*}).
     */
    public record AsyncSettings(@Valid @DefaultValue SocialCrawler socialCrawler) {
    }

    /**
     * Executor {@code socialCrawlerExecutor} crawl metrics song song theo tài khoản. Số thread và hàng đợi luôn có
     * giới hạn (OWASP A06 — giới hạn tài nguyên).
     * <p>
     * {@code ThreadPoolExecutor} chỉ tạo thêm thread vượt {@code corePoolSize} khi hàng đợi đã <b>đầy</b>: với core 4,
     * max 8, queue 100 thì tới 104 task vẫn chỉ 4 thread chạy. Số việc chạy song song thực tế ≈ {@code corePoolSize} →
     * mặc định đặt core = max; core thread tự dừng khi rảnh ({@code allowCoreThreadTimeOut}) nên không giữ thread
     * giữa 2 lần chạy job.
     *
     * @param awaitTermination thời gian tối đa chờ task đang chạy hoàn tất khi tắt app
     */
    public record SocialCrawler(
            @Positive @Max(64) @DefaultValue("8") int corePoolSize,
            @Positive @Max(64) @DefaultValue("8") int maxPoolSize,
            @PositiveOrZero @Max(10000) @DefaultValue("100") int queueCapacity,
            @NotNull @DefaultValue("30s") Duration awaitTermination) {

        /**
         * Tên bean executor: {@code @Async(AppProperties.SocialCrawler.EXECUTOR_BEAN_NAME)}. Đặt ở đây (không ở
         * {@code config/AsyncConfig}) để service dùng được mà không import package {@code config} — tránh vòng phụ
         * thuộc {@code service} ↔ {@code config}.
         */
        public static final String EXECUTOR_BEAN_NAME = "socialCrawlerExecutor";

        @AssertTrue(message = "app.async.social-crawler.core-pool-size must not exceed max-pool-size")
        public boolean isCorePoolSizeWithinMax() {
            return corePoolSize <= maxPoolSize;
        }

        @AssertTrue(message = "app.async.social-crawler.await-termination must not be negative")
        public boolean isAwaitTerminationNotNegative() {
            return awaitTermination == null || !awaitTermination.isNegative();
        }
    }

    /**
     * JMS ({@code app.jms.*}).
     *
     * @param embeddedBrokerProperties file "broker properties" của Artemis (location kiểu Spring, vd
     *                                 {@code classpath:...}) nạp vào broker <b>embedded</b> theo thứ tự, file sau ghi đè
     *                                 khoá trùng của file trước ({@code config/ArtemisEmbeddedConfig}). File đầu tiên
     *                                 cũng là file broker riêng của docker compose nạp → retry/DLQ chỉ khai báo 1 nơi.
     */
    public record JmsSettings(
            @NotEmpty @DefaultValue("classpath:artemis/broker.properties")
            List<@NotBlank String> embeddedBrokerProperties) {
    }

    /**
     * Background job ({@code app.jobs.*}). Lịch chạy (cron) đọc trực tiếp bằng placeholder trong {@code @Scheduled}
     * nên không nằm trong record này.
     */
    public record Jobs(
            @Valid @DefaultValue UpdateMetrics updateMetrics,
            @Valid @DefaultValue ResendImportCompleted resendImportCompleted) {
    }

    /**
     * Job {@code resendImportCompletedJob}: gửi lại message {@code IMPORT_COMPLETED} cho các lần import kẹt ở
     * {@code PENDING} (producer gửi lỗi, broker mất message) — listener idempotent nên gửi trùng không cộng 2 lần.
     *
     * @param minAge         chỉ gửi lại import đã {@code PENDING} (tính từ lần ghi/gửi gần nhất) lâu hơn khoảng này.
     *                       Nên lớn hơn tổng thời gian broker retry trước khi vào DLQ (~1 phút, xem
     *                       {@code artemis/broker.properties}) để không gửi trùng message đang được retry
     * @param batchSize      số import tối đa gửi lại mỗi lần chạy (phần còn lại để lần sau)
     * @param lockAtMostFor  thời hạn tối đa giữ khoá ShedLock — phải nhỏ hơn chu kỳ cron
     * @param lockAtLeastFor thời gian tối thiểu giữ khoá (đồng hồ các instance lệch nhau không làm chạy lại);
     *                       không vượt {@code lockAtMostFor}
     */
    public record ResendImportCompleted(
            @NotNull @DefaultValue("10m") Duration minAge,
            @Positive @Max(1000) @DefaultValue("100") int batchSize,
            @NotNull @DefaultValue("9m") Duration lockAtMostFor,
            @NotNull @DefaultValue("1m") Duration lockAtLeastFor) {

        @AssertTrue(message = "app.jobs.resend-import-completed.min-age and lock-at-most-for must be positive")
        public boolean isDurationsPositive() {
            return (minAge == null || (!minAge.isNegative() && !minAge.isZero()))
                    && (lockAtMostFor == null || (!lockAtMostFor.isNegative() && !lockAtMostFor.isZero()));
        }

        @AssertTrue(message = "app.jobs.resend-import-completed.lock-at-least-for must be between 0 and"
                + " lock-at-most-for")
        public boolean isLockAtLeastForWithinLockAtMostFor() {
            return lockAtLeastFor == null || lockAtMostFor == null
                    || (!lockAtLeastFor.isNegative() && lockAtLeastFor.compareTo(lockAtMostFor) <= 0);
        }
    }

    /**
     * Job {@code updateSocialMetricsJob}.
     *
     * @param batchSize số bài mỗi lần gọi API mạng xã hội (không vượt giới hạn 100 id/lần của client)
     * @param timeout   thời lượng tối đa của 1 lần chạy, tính từ lúc bắt đầu (gồm cả lúc gửi task). Quá hạn: tài khoản
     *                  chưa xong tính là lỗi, task còn chạy dừng ở bước kế tiếp và bỏ kết quả (không ghi DB). Phải
     *                  nhỏ hơn chu kỳ cron
     * @param lockAtMostFor  thời hạn tối đa giữ khoá chung giữa các instance (ShedLock) — instance chết giữa chừng
     *                       thì khoá tự hết hạn sau khoảng này. Phải lớn hơn {@code timeout} (job chưa xong không mất
     *                       khoá) và nhỏ hơn chu kỳ cron (lần chạy sau không bị chặn bởi khoá của instance đã chết)
     * @param lockAtLeastFor thời gian tối thiểu giữ khoá kể cả khi job xong sớm — đồng hồ các instance lệch nhau vài
     *                       giây, nếu nhả khoá ngay thì instance kích hoạt muộn hơn vẫn lấy được khoá và chạy lần nữa.
     *                       Không vượt {@code lockAtMostFor}
     */
    public record UpdateMetrics(
            @Positive @Max(100) @DefaultValue("100") int batchSize,
            @NotNull @DefaultValue("50m") Duration timeout,
            @NotNull @DefaultValue("55m") Duration lockAtMostFor,
            @NotNull @DefaultValue("5m") Duration lockAtLeastFor) {

        @AssertTrue(message = "app.jobs.update-metrics.timeout must be positive")
        public boolean isTimeoutPositive() {
            return timeout == null || (!timeout.isNegative() && !timeout.isZero());
        }

        @AssertTrue(message = "app.jobs.update-metrics.lock-at-most-for must be greater than timeout")
        public boolean isLockAtMostForLongerThanTimeout() {
            return timeout == null || lockAtMostFor == null || lockAtMostFor.compareTo(timeout) > 0;
        }

        @AssertTrue(message = "app.jobs.update-metrics.lock-at-least-for must be between 0 and lock-at-most-for")
        public boolean isLockAtLeastForWithinLockAtMostFor() {
            return lockAtLeastFor == null || lockAtMostFor == null
                    || (!lockAtLeastFor.isNegative() && lockAtLeastFor.compareTo(lockAtMostFor) <= 0);
        }
    }
}

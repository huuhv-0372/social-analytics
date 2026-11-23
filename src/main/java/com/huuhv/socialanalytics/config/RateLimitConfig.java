package com.huuhv.socialanalytics.config;

import io.github.bucket4j.TimeMeter;
import io.github.bucket4j.distributed.jdbc.PrimaryKeyMapper;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.mysql.Bucket4jMySQL;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;

/**
 * Rate limit dùng chung giữa các instance (OWASP A06): trạng thái bucket của Bucket4j lưu trong bảng
 * {@code rate_limit_buckets} (Flyway {@code V1_1}). Mỗi lần kiểm tra là 1 transaction JDBC riêng với
 * {@code SELECT ... FOR UPDATE} → nhiều instance / nhiều thread cùng user không cùng lọt qua; restart không mất mốc chặn.
 */
@Configuration
public class RateLimitConfig {

    static final String TABLE = "rate_limit_buckets";

    /**
     * Thời gian lấy từ bean {@link Clock} (test cố định được) thay cho đồng hồ hệ thống mặc định của Bucket4j.
     * Các instance dùng chung bucket nên đồng hồ máy chủ phải được đồng bộ (NTP).
     */
    @Bean
    public ProxyManager<String> rateLimitProxyManager(DataSource dataSource, Clock clock) {
        return Bucket4jMySQL.selectForUpdateBasedBuilder(dataSource)
                .primaryKeyMapper(PrimaryKeyMapper.STRING)
                .table(TABLE)
                .idColumn("id")
                .stateColumn("state")
                .clientClock(new ClockTimeMeter(clock))
                .build();
    }

    /** Chuyển {@link Clock} sang {@link TimeMeter} (nano giây kể từ epoch) cho Bucket4j. */
    record ClockTimeMeter(Clock clock) implements TimeMeter {

        @Override
        public long currentTimeNanos() {
            Instant now = clock.instant();
            return Math.addExact(Math.multiplyExact(now.getEpochSecond(), 1_000_000_000L), now.getNano());
        }

        @Override
        public boolean isWallClockBased() {
            return true;
        }
    }
}

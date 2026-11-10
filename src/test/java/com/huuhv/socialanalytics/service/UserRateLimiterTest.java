package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.TestcontainersConfiguration;
import com.huuhv.socialanalytics.config.RateLimitConfig;
import com.huuhv.socialanalytics.exception.TooManyRequestsException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bucket4j trên MySQL thật (Testcontainers): trạng thái bucket phải dùng chung giữa các instance và chịu được
 * request song song. Không bọc test trong transaction — Bucket4j tự mở/commit transaction JDBC của nó.
 * Bucket còn lại giữa các test (context dùng chung) nên mỗi test dùng userId riêng.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, RateLimitConfig.class, UserRateLimiter.class,
        UserRateLimiterTest.MutableClockConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserRateLimiterTest {

    private static final String ACTION = "export-report";
    private static final Duration INTERVAL = Duration.ofSeconds(10);
    private static final AtomicLong NEXT_USER_ID = new AtomicLong(1);

    @Autowired
    private UserRateLimiter limiter;

    @Autowired
    private MutableClock clock;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void rejectsSecondCallWithinIntervalWithRemainingTime() {
        long userId = newUserId();
        limiter.acquire(ACTION, userId, INTERVAL);
        clock.advance(Duration.ofSeconds(4));

        assertThatThrownBy(() -> limiter.acquire(ACTION, userId, INTERVAL))
                .isInstanceOfSatisfying(TooManyRequestsException.class,
                        ex -> assertThat(ex.getRetryAfter().toMillis()).isBetween(5_999L, 6_000L));
    }

    @Test
    void allowsAgainOnceIntervalHasPassed() {
        long userId = newUserId();
        limiter.acquire(ACTION, userId, INTERVAL);
        clock.advance(INTERVAL);

        assertThatCode(() -> limiter.acquire(ACTION, userId, INTERVAL)).doesNotThrowAnyException();
    }

    @Test
    void rejectedCallDoesNotExtendTheWindow() {
        long userId = newUserId();
        limiter.acquire(ACTION, userId, INTERVAL);
        clock.advance(Duration.ofSeconds(5));
        assertThatThrownBy(() -> limiter.acquire(ACTION, userId, INTERVAL)).isInstanceOf(TooManyRequestsException.class);

        clock.advance(Duration.ofSeconds(5));

        assertThatCode(() -> limiter.acquire(ACTION, userId, INTERVAL)).doesNotThrowAnyException();
    }

    @Test
    void limitsAreIndependentPerUserAndAction() {
        long userId = newUserId();
        limiter.acquire(ACTION, userId, INTERVAL);

        assertThatCode(() -> limiter.acquire(ACTION, newUserId(), INTERVAL)).doesNotThrowAnyException();
        assertThatCode(() -> limiter.acquire("import-posts", userId, INTERVAL)).doesNotThrowAnyException();
    }

    @Test
    void zeroIntervalDisablesLimitWithoutTouchingDatabase() {
        long userId = newUserId();
        limiter.acquire(ACTION, userId, Duration.ZERO);

        assertThatCode(() -> limiter.acquire(ACTION, userId, Duration.ZERO)).doesNotThrowAnyException();
        assertThat(countBuckets(ACTION + ":" + userId + ":%")).isZero();
    }

    @Test
    void stateIsSharedBetweenInstancesThroughDatabase() {
        long userId = newUserId();
        // "Instance" thứ 2: ProxyManager riêng (như 1 JVM khác sau load balancer), cùng DB
        UserRateLimiter otherInstance = new UserRateLimiter(
                new RateLimitConfig().rateLimitProxyManager(dataSource, clock));

        limiter.acquire(ACTION, userId, INTERVAL);

        assertThatThrownBy(() -> otherInstance.acquire(ACTION, userId, INTERVAL))
                .isInstanceOf(TooManyRequestsException.class);
        assertThat(countBuckets(UserRateLimiter.bucketKey(ACTION, userId, INTERVAL))).isEqualTo(1);
    }

    @Test
    void changedIntervalUsesNewBucketInsteadOfStaleStoredConfiguration() {
        long userId = newUserId();
        limiter.acquire(ACTION, userId, INTERVAL);

        assertThatCode(() -> limiter.acquire(ACTION, userId, Duration.ofSeconds(5))).doesNotThrowAnyException();
    }

    @Test
    void onlyOneOfConcurrentCallsFromSameUserIsAllowed() throws Exception {
        long userId = newUserId();
        int threads = 16;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        limiter.acquire(ACTION, userId, INTERVAL);
                        allowed.incrementAndGet();
                    } catch (TooManyRequestsException expected) {
                        // các request còn lại bị chặn
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(allowed).hasValue(1);
    }

    private static long newUserId() {
        return NEXT_USER_ID.getAndIncrement();
    }

    private int countBuckets(String idPattern) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rate_limit_buckets WHERE id LIKE ?", Integer.class, idPattern);
        return count == null ? 0 : count;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MutableClockConfig {

        @Bean
        MutableClock clock() {
            return new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        }
    }

    /** Clock điều khiển được trong test (dịch thời gian không cần sleep). Thread-safe cho test song song. */
    static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

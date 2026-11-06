package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.exception.TooManyRequestsException;
import org.junit.jupiter.api.Test;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserRateLimiterTest {

    private static final String ACTION = "export-report";
    private static final Duration INTERVAL = Duration.ofSeconds(10);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final UserRateLimiter limiter = new UserRateLimiter(clock);

    @Test
    void rejectsSecondCallWithinIntervalWithRemainingTime() {
        limiter.acquire(ACTION, 1L, INTERVAL);
        clock.advance(Duration.ofSeconds(4));

        assertThatThrownBy(() -> limiter.acquire(ACTION, 1L, INTERVAL))
                .isInstanceOfSatisfying(TooManyRequestsException.class,
                        ex -> assertThat(ex.getRetryAfter()).isEqualTo(Duration.ofSeconds(6)));
    }

    @Test
    void rejectedCallDoesNotExtendTheWindow() {
        limiter.acquire(ACTION, 1L, INTERVAL);
        clock.advance(Duration.ofSeconds(5));
        assertThatThrownBy(() -> limiter.acquire(ACTION, 1L, INTERVAL)).isInstanceOf(TooManyRequestsException.class);

        clock.advance(Duration.ofSeconds(5));

        assertThatCode(() -> limiter.acquire(ACTION, 1L, INTERVAL)).doesNotThrowAnyException();
    }

    @Test
    void limitsAreIndependentPerUserAndAction() {
        limiter.acquire(ACTION, 1L, INTERVAL);

        assertThatCode(() -> limiter.acquire(ACTION, 2L, INTERVAL)).doesNotThrowAnyException();
        assertThatCode(() -> limiter.acquire("import-posts", 1L, INTERVAL)).doesNotThrowAnyException();
    }

    @Test
    void zeroIntervalDisablesLimit() {
        limiter.acquire(ACTION, 1L, Duration.ZERO);

        assertThatCode(() -> limiter.acquire(ACTION, 1L, Duration.ZERO)).doesNotThrowAnyException();
    }

    @Test
    void evictsExpiredEntriesWhenMapGrowsPastThreshold() {
        for (long userId = 1; userId <= UserRateLimiter.CLEANUP_THRESHOLD; userId++) {
            limiter.acquire(ACTION, userId, INTERVAL);
        }
        clock.advance(INTERVAL);

        limiter.acquire(ACTION, 0L, INTERVAL);

        assertThat(limiter.trackedEntries()).isEqualTo(1);
    }

    @Test
    void onlyOneOfConcurrentCallsFromSameUserIsAllowed() throws Exception {
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
                        limiter.acquire(ACTION, 1L, INTERVAL);
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

    /** Clock điều khiển được trong test (dịch thời gian không cần sleep). */
    private static final class MutableClock extends Clock {

        private Instant now;

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

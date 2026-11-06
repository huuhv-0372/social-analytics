package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.exception.TooManyRequestsException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Giới hạn tần suất thao tác tốn tài nguyên theo từng user (OWASP A06): mỗi {@code (action, userId)} chỉ được thực hiện
 * 1 lần trong {@code minInterval}. Kiểm tra + ghi nhận nguyên tử ({@link ConcurrentMap#compute}) nên 2 request song song
 * của cùng user không cùng lọt qua.
 * <p>
 * Trạng thái giữ trong bộ nhớ của 1 instance — đủ cho triển khai 1 node; chạy nhiều instance thì cần store dùng chung
 * (Redis/Bucket4j). Mục đã hết hạn được dọn khi map vượt {@link #CLEANUP_THRESHOLD} để map không phình vô hạn.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserRateLimiter {

    static final int CLEANUP_THRESHOLD = 10_000;

    private final Clock clock;
    private final ConcurrentMap<Key, Instant> nextAllowedAt = new ConcurrentHashMap<>();

    /**
     * @throws TooManyRequestsException khi user đã thực hiện {@code action} trong vòng {@code minInterval}
     */
    public void acquire(String action, long userId, Duration minInterval) {
        if (minInterval.isZero()) {
            return;
        }
        Instant now = clock.instant();
        Instant[] blockedUntil = new Instant[1];
        nextAllowedAt.compute(new Key(action, userId), (key, allowedAt) -> {
            if (allowedAt != null && now.isBefore(allowedAt)) {
                blockedUntil[0] = allowedAt;
                return allowedAt;
            }
            return now.plus(minInterval);
        });
        if (blockedUntil[0] != null) {
            // Audit (OWASP A09): user bị chặn vì gọi quá tần suất
            log.warn("User {} rate limited for {}", userId, action);
            throw new TooManyRequestsException(Duration.between(now, blockedUntil[0]));
        }
        if (nextAllowedAt.size() > CLEANUP_THRESHOLD) {
            nextAllowedAt.values().removeIf(allowedAt -> !now.isBefore(allowedAt));
        }
    }

    /** Số mục đang theo dõi (cho test kiểm tra việc dọn mục hết hạn). */
    int trackedEntries() {
        return nextAllowedAt.size();
    }

    private record Key(String action, long userId) {
    }
}

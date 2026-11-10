package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.exception.TooManyRequestsException;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Giới hạn tần suất thao tác tốn tài nguyên theo từng user (OWASP A06): mỗi {@code (action, userId)} chỉ được thực hiện
 * 1 lần trong {@code minInterval}.
 * <p>
 * Trạng thái lưu ở MySQL qua Bucket4j ({@code config/RateLimitConfig}) → giới hạn đúng khi chạy nhiều instance sau load
 * balancer và không mất khi restart. Bucket dung lượng 1 token, nạp lại 1 token sau {@code minInterval}; kiểm tra +
 * trừ token nguyên tử ({@code SELECT ... FOR UPDATE}) nên request song song của cùng user chỉ 1 request lọt qua.
 * <p>
 * Khoá bucket gồm cả {@code minInterval}: đổi cấu hình thì dùng bucket mới, không bị kẹt cấu hình cũ đã lưu trong DB.
 * Lỗi DB khi kiểm tra → exception lan ra (fail closed, request bị từ chối với 500), không bỏ qua giới hạn.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserRateLimiter {

    private final ProxyManager<String> rateLimitProxyManager;

    /**
     * @throws TooManyRequestsException khi user đã thực hiện {@code action} trong vòng {@code minInterval}
     */
    public void acquire(String action, long userId, Duration minInterval) {
        if (minInterval.isZero()) {
            return;
        }
        ConsumptionProbe probe = rateLimitProxyManager.builder()
                .build(bucketKey(action, userId, minInterval), () -> configuration(minInterval))
                .tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            // Audit (OWASP A09): user bị chặn vì gọi quá tần suất
            log.warn("User {} rate limited for {}", userId, action);
            throw new TooManyRequestsException(Duration.ofNanos(probe.getNanosToWaitForRefill()));
        }
    }

    static String bucketKey(String action, long userId, Duration minInterval) {
        return action + ":" + userId + ":" + minInterval.toMillis();
    }

    private static BucketConfiguration configuration(Duration minInterval) {
        return BucketConfiguration.builder()
                .addLimit(limit -> limit.capacity(1).refillGreedy(1, minInterval))
                .build();
    }
}

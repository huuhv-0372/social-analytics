package com.huuhv.socialanalytics.service;

import io.github.bucket4j.distributed.proxy.ProxyManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * OWASP A10 — fail closed: không kiểm tra được giới hạn (DB lỗi) thì exception lan ra, request không được cho qua.
 */
@ExtendWith(MockitoExtension.class)
class UserRateLimiterFailClosedTest {

    @Mock
    private ProxyManager<String> proxyManager;

    @Test
    void storageFailurePropagatesInsteadOfAllowingRequest() {
        when(proxyManager.builder()).thenThrow(new IllegalStateException("database unavailable"));
        UserRateLimiter limiter = new UserRateLimiter(proxyManager);

        assertThatThrownBy(() -> limiter.acquire("export-report", 1L, Duration.ofSeconds(10)))
                .isInstanceOf(IllegalStateException.class);
    }
}

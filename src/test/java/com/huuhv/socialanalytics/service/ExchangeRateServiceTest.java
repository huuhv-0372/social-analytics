package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.client.soap.ExchangeRateQuote;
import com.huuhv.socialanalytics.client.soap.ExchangeRateSoapClient;
import com.huuhv.socialanalytics.client.soap.ExchangeRateUnavailableException;
import com.huuhv.socialanalytics.config.properties.AppProperties;
import com.huuhv.socialanalytics.dto.response.ExchangeRateResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExchangeRateServiceTest {

    private static final Instant START = Instant.parse("2026-11-13T05:42:17Z");
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(1);
    private static final ExchangeRateQuote QUOTE = new ExchangeRateQuote("USD", "VND", new BigDecimal("25400.5"),
            Instant.parse("2026-11-13T05:00:00Z"));
    private static final ExchangeRateQuote NEWER_QUOTE = new ExchangeRateQuote("USD", "VND",
            new BigDecimal("25500.25"), Instant.parse("2026-11-13T06:00:00Z"));

    @Mock
    private ExchangeRateSoapClient client;

    private final MutableClock clock = new MutableClock(START);
    private ExchangeRateService service;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties(null, null, null, null, null, null, null,
                new AppProperties.Soap(new AppProperties.ExchangeRate(URI.create("http://localhost:8080/services"),
                        Duration.ofSeconds(2), Duration.ofSeconds(3), "USD", "VND", CACHE_TTL, RETRY_AFTER_FAILURE,
                        new AppProperties.MockEndpoint(false))));
        service = new ExchangeRateService(client, properties, clock);
    }

    @Test
    void fetchesConfiguredPairAndReusesItWithinCacheTtl() {
        when(client.getExchangeRate("USD", "VND")).thenReturn(QUOTE);

        ExchangeRateResponse first = service.getCurrentRate();
        clock.advance(CACHE_TTL.minusSeconds(1));
        ExchangeRateResponse second = service.getCurrentRate();

        assertThat(first).isEqualTo(new ExchangeRateResponse("USD", "VND", new BigDecimal("25400.5"),
                QUOTE.asOf(), START, false));
        assertThat(second).isEqualTo(first);
        verify(client, times(1)).getExchangeRate("USD", "VND");
    }

    @Test
    void refetchesAfterCacheTtl() {
        when(client.getExchangeRate("USD", "VND")).thenReturn(QUOTE, NEWER_QUOTE);

        service.getCurrentRate();
        clock.advance(CACHE_TTL);
        ExchangeRateResponse refreshed = service.getCurrentRate();

        assertThat(refreshed.rate()).isEqualByComparingTo("25500.25");
        assertThat(refreshed.fetchedAt()).isEqualTo(START.plus(CACHE_TTL));
        assertThat(refreshed.stale()).isFalse();
    }

    @Test
    void failureWithCachedValueReturnsStaleValue() {
        when(client.getExchangeRate("USD", "VND")).thenReturn(QUOTE)
                .thenThrow(new ExchangeRateUnavailableException("Exchange rate service call failed: X"));

        service.getCurrentRate();
        clock.advance(CACHE_TTL.plusMinutes(5));
        ExchangeRateResponse stale = service.getCurrentRate();

        // Giá trị cũ + thời điểm lấy cũ, đánh dấu stale
        assertThat(stale.rate()).isEqualByComparingTo("25400.5");
        assertThat(stale.fetchedAt()).isEqualTo(START);
        assertThat(stale.stale()).isTrue();
    }

    @Test
    void failureWithoutCachedValueReturnsNull() {
        when(client.getExchangeRate("USD", "VND"))
                .thenThrow(new ExchangeRateUnavailableException("Exchange rate service call failed: X"));

        assertThat(service.getCurrentRate()).isNull();
    }

    @Test
    void unexpectedErrorIsNotPropagated() {
        when(client.getExchangeRate("USD", "VND")).thenThrow(new IllegalStateException("bug"));

        assertThat(service.getCurrentRate()).isNull();
    }

    @Test
    void doesNotRetryWithinRetryAfterFailureThenRecovers() {
        when(client.getExchangeRate("USD", "VND"))
                .thenThrow(new ExchangeRateUnavailableException("Exchange rate service call failed: X"))
                .thenReturn(QUOTE);

        assertThat(service.getCurrentRate()).isNull();
        clock.advance(RETRY_AFTER_FAILURE.minusSeconds(1));
        assertThat(service.getCurrentRate()).isNull();
        verify(client, times(1)).getExchangeRate("USD", "VND");

        clock.advance(Duration.ofSeconds(1));
        ExchangeRateResponse recovered = service.getCurrentRate();

        assertThat(recovered.stale()).isFalse();
        assertThat(recovered.fetchedAt()).isEqualTo(START.plus(RETRY_AFTER_FAILURE));
        verify(client, times(2)).getExchangeRate("USD", "VND");
    }

    @Test
    void staleFlagClearsAfterSuccessfulRefetch() {
        when(client.getExchangeRate("USD", "VND")).thenReturn(QUOTE)
                .thenThrow(new ExchangeRateUnavailableException("Exchange rate service call failed: X"))
                .thenReturn(NEWER_QUOTE);

        service.getCurrentRate();
        clock.advance(CACHE_TTL);
        assertThat(service.getCurrentRate().stale()).isTrue();
        clock.advance(RETRY_AFTER_FAILURE);

        ExchangeRateResponse recovered = service.getCurrentRate();

        assertThat(recovered.stale()).isFalse();
        assertThat(recovered.rate()).isEqualByComparingTo("25500.25");
    }

    /** Đang có thread lấy tỷ giá (SOAP chậm) → request khác dùng ngay giá trị cũ, không chờ, không gọi thêm. */
    @Test
    void concurrentRequestUsesCurrentValueWhileRefreshIsInProgress() throws Exception {
        CountDownLatch callStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(client.getExchangeRate("USD", "VND")).thenReturn(QUOTE).thenAnswer(invocation -> {
            callStarted.countDown();
            release.await(5, TimeUnit.SECONDS);
            return NEWER_QUOTE;
        });
        service.getCurrentRate();
        clock.advance(CACHE_TTL);

        CompletableFuture<ExchangeRateResponse> refreshing = CompletableFuture.supplyAsync(service::getCurrentRate);
        assertThat(callStarted.await(5, TimeUnit.SECONDS)).isTrue();
        ExchangeRateResponse meanwhile = service.getCurrentRate();
        release.countDown();

        assertThat(meanwhile.rate()).isEqualByComparingTo("25400.5");
        // Chưa có lần gọi nào lỗi → không phải "dữ liệu cũ"
        assertThat(meanwhile.stale()).isFalse();
        assertThat(refreshing.get(5, TimeUnit.SECONDS).rate()).isEqualByComparingTo("25500.25");
        verify(client, times(2)).getExchangeRate("USD", "VND");
        verifyNoMoreInteractions(client);
    }

    private static final class MutableClock extends Clock {

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

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
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

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
        service = new ExchangeRateService(client, properties(URI.create("http://localhost:8080/services"), true),
                clock);
    }

    private static AppProperties properties(URI url, boolean warmUpOnStartup) {
        return new AppProperties(null, null, null, null, null, null, null, null,
                new AppProperties.Soap(new AppProperties.ExchangeRate(url, Duration.ofSeconds(2),
                        Duration.ofSeconds(3), "USD", "VND", CACHE_TTL, RETRY_AFTER_FAILURE,
                        new AppProperties.MockEndpoint(false, DataSize.ofKilobytes(16)), warmUpOnStartup)));
    }

    /** Không cấu hình url = tắt tỷ giá: không gọi SOAP (production không có mặc định trỏ vào chính app). */
    @Test
    void disabledWithoutUrlNeverCallsSoap() {
        ExchangeRateService disabled = new ExchangeRateService(client, properties(null, true), clock);

        disabled.warmUp();
        disabled.refreshIfDue();

        assertThat(disabled.getCurrentRate()).isNull();
        verifyNoInteractions(client);
    }

    /** Request chỉ đọc cache: chưa có giá trị → null ngay, không gọi SOAP trên thread request. */
    @Test
    void getCurrentRateNeverCallsSoap() {
        assertThat(service.getCurrentRate()).isNull();

        when(client.getExchangeRate("USD", "VND")).thenReturn(QUOTE);
        service.refreshIfDue();
        clock.advance(CACHE_TTL.multipliedBy(5));
        service.getCurrentRate();

        // Chỉ 1 lời gọi (của refreshIfDue) dù cache đã hết hạn từ lâu
        verify(client, times(1)).getExchangeRate("USD", "VND");
    }

    @Test
    void warmUpFetchesOnceSoFirstPageLoadIsServedFromCache() {
        when(client.getExchangeRate("USD", "VND")).thenReturn(QUOTE);

        service.warmUp();
        ExchangeRateResponse rate = service.getCurrentRate();

        assertThat(rate.rate()).isEqualByComparingTo("25400.5");
        verify(client, times(1)).getExchangeRate("USD", "VND");
    }

    @Test
    void warmUpCanBeTurnedOff() {
        new ExchangeRateService(client, properties(URI.create("http://localhost:8080/services"), false), clock)
                .warmUp();

        verifyNoInteractions(client);
    }

    @Test
    void refreshFetchesConfiguredPairAndSkipsWithinCacheTtl() {
        when(client.getExchangeRate("USD", "VND")).thenReturn(QUOTE);

        service.refreshIfDue();
        clock.advance(CACHE_TTL.minusSeconds(1));
        service.refreshIfDue();

        assertThat(service.getCurrentRate()).isEqualTo(new ExchangeRateResponse("USD", "VND",
                new BigDecimal("25400.5"), QUOTE.asOf(), START, false));
        verify(client, times(1)).getExchangeRate("USD", "VND");
    }

    @Test
    void refreshFetchesAgainAfterCacheTtl() {
        when(client.getExchangeRate("USD", "VND")).thenReturn(QUOTE, NEWER_QUOTE);

        service.refreshIfDue();
        clock.advance(CACHE_TTL);
        service.refreshIfDue();
        ExchangeRateResponse refreshed = service.getCurrentRate();

        assertThat(refreshed.rate()).isEqualByComparingTo("25500.25");
        assertThat(refreshed.fetchedAt()).isEqualTo(START.plus(CACHE_TTL));
        assertThat(refreshed.stale()).isFalse();
    }

    @Test
    void failureWithCachedValueKeepsValueMarkedStale() {
        when(client.getExchangeRate("USD", "VND")).thenReturn(QUOTE)
                .thenThrow(new ExchangeRateUnavailableException("Exchange rate service call failed: X"));

        service.refreshIfDue();
        clock.advance(CACHE_TTL);
        service.refreshIfDue();
        ExchangeRateResponse stale = service.getCurrentRate();

        // Giá trị cũ + thời điểm lấy cũ, đánh dấu stale
        assertThat(stale.rate()).isEqualByComparingTo("25400.5");
        assertThat(stale.fetchedAt()).isEqualTo(START);
        assertThat(stale.stale()).isTrue();
    }

    /** Job làm mới không chạy (hoặc bị chặn) → giá trị quá 2 × cache-ttl hiển thị là dữ liệu cũ dù chưa có lỗi. */
    @Test
    void valueNotRefreshedForTwiceCacheTtlIsStale() {
        when(client.getExchangeRate("USD", "VND")).thenReturn(QUOTE);
        service.refreshIfDue();

        clock.advance(CACHE_TTL.multipliedBy(2).minusSeconds(1));
        assertThat(service.getCurrentRate().stale()).isFalse();
        clock.advance(Duration.ofSeconds(1));
        assertThat(service.getCurrentRate().stale()).isTrue();
    }

    @Test
    void failureWithoutCachedValueReturnsNull() {
        when(client.getExchangeRate("USD", "VND"))
                .thenThrow(new ExchangeRateUnavailableException("Exchange rate service call failed: X"));

        service.refreshIfDue();

        assertThat(service.getCurrentRate()).isNull();
    }

    @Test
    void unexpectedErrorIsNotPropagated() {
        when(client.getExchangeRate("USD", "VND")).thenThrow(new IllegalStateException("bug"));

        service.refreshIfDue();

        assertThat(service.getCurrentRate()).isNull();
    }

    @Test
    void doesNotRetryWithinRetryAfterFailureThenRecovers() {
        when(client.getExchangeRate("USD", "VND"))
                .thenThrow(new ExchangeRateUnavailableException("Exchange rate service call failed: X"))
                .thenReturn(QUOTE);

        service.refreshIfDue();
        clock.advance(RETRY_AFTER_FAILURE.minusSeconds(1));
        service.refreshIfDue();
        assertThat(service.getCurrentRate()).isNull();
        verify(client, times(1)).getExchangeRate("USD", "VND");

        clock.advance(Duration.ofSeconds(1));
        service.refreshIfDue();
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

        service.refreshIfDue();
        clock.advance(CACHE_TTL);
        service.refreshIfDue();
        assertThat(service.getCurrentRate().stale()).isTrue();
        clock.advance(RETRY_AFTER_FAILURE);
        service.refreshIfDue();

        ExchangeRateResponse recovered = service.getCurrentRate();

        assertThat(recovered.stale()).isFalse();
        assertThat(recovered.rate()).isEqualByComparingTo("25500.25");
    }

    /**
     * Đang làm mới (SOAP chậm): request đọc ngay giá trị hiện có (không chờ), lần làm mới khác bỏ qua thay vì gọi
     * thêm / xếp hàng chờ khoá.
     */
    @Test
    void concurrentRefreshIsSkippedAndReadsDoNotWait() throws Exception {
        CountDownLatch callStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(client.getExchangeRate("USD", "VND")).thenReturn(QUOTE).thenAnswer(invocation -> {
            callStarted.countDown();
            release.await(5, TimeUnit.SECONDS);
            return NEWER_QUOTE;
        });
        service.refreshIfDue();
        clock.advance(CACHE_TTL);

        CompletableFuture<Void> refreshing = CompletableFuture.runAsync(service::refreshIfDue);
        assertThat(callStarted.await(5, TimeUnit.SECONDS)).isTrue();
        // Chạy ở thread khác + timeout ngắn: nếu chờ khoá thì get() hết giờ trước khi release
        CompletableFuture.runAsync(service::refreshIfDue).get(1, TimeUnit.SECONDS);
        ExchangeRateResponse meanwhile = CompletableFuture.supplyAsync(service::getCurrentRate)
                .get(1, TimeUnit.SECONDS);
        release.countDown();
        refreshing.get(5, TimeUnit.SECONDS);

        assertThat(meanwhile.rate()).isEqualByComparingTo("25400.5");
        // Chưa có lần gọi nào lỗi, chưa quá 2 × TTL → không phải "dữ liệu cũ"
        assertThat(meanwhile.stale()).isFalse();
        assertThat(service.getCurrentRate().rate()).isEqualByComparingTo("25500.25");
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

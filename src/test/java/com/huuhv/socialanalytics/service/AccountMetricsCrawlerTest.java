package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.client.social.InvalidAccessTokenException;
import com.huuhv.socialanalytics.client.social.PostMetrics;
import com.huuhv.socialanalytics.client.social.SocialPlatformClient;
import com.huuhv.socialanalytics.client.social.SocialPlatformClients;
import com.huuhv.socialanalytics.client.social.SocialPlatformException;
import com.huuhv.socialanalytics.config.properties.AppProperties;
import com.huuhv.socialanalytics.dto.response.PostRef;
import com.huuhv.socialanalytics.enums.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountMetricsCrawlerTest {

    private static final long ACCOUNT_ID = 7L;
    private static final long USER_ID = 3L;
    private static final String TOKEN = "plain-access-token";
    // Server chạy ở +07: collectedAt, last_updated_at và hạn token đều lưu theo UTC, không theo giờ server
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-07T03:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
    private static final LocalDateTime NOW_UTC = LocalDateTime.of(2026, 11, 7, 3, 0);

    @Mock
    private SocialAccountService socialAccountService;

    @Mock
    private PostService postService;

    @Mock
    private SocialMetricService socialMetricService;

    @Mock
    private SocialPlatformClients socialPlatformClients;

    @Mock
    private SocialPlatformClient client;

    private AccountMetricsCrawler crawler;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties(null, null, null, null, null,
                new AppProperties.Jobs(new AppProperties.UpdateMetrics(2, Duration.ofMinutes(1))));
        crawler = new AccountMetricsCrawler(socialAccountService, postService, socialMetricService,
                socialPlatformClients, properties, CLOCK);
    }

    @Test
    void savesSnapshotsForAllPostsInBatches() {
        givenAccount(TOKEN, NOW_UTC.plusDays(30));
        givenPosts(new PostRef(1L, "a"), new PostRef(2L, "b"), new PostRef(3L, "c"));
        when(client.fetchMetrics(TOKEN, List.of("a", "b")))
                .thenReturn(List.of(metrics("a", 10), metrics("b", 20)));
        when(client.fetchMetrics(TOKEN, List.of("c"))).thenReturn(List.of(metrics("c", 30)));

        CrawlResult result = crawler.crawlNow(ACCOUNT_ID);

        assertThat(result).isEqualTo(CrawlResult.succeeded(ACCOUNT_ID, 3));
        verify(socialMetricService).recordSnapshots(ACCOUNT_ID, List.of(
                new CrawledMetric(1L, 10, 1, 2, 100),
                new CrawledMetric(2L, 20, 1, 2, 100),
                new CrawledMetric(3L, 30, 1, 2, 100)), NOW_UTC, NOW_UTC);
    }

    @Test
    void tokenWithoutExpiryIsUsed() {
        givenAccount(TOKEN, null);
        givenPosts(new PostRef(1L, "a"));
        when(client.fetchMetrics(TOKEN, List.of("a"))).thenReturn(List.of(metrics("a", 10)));

        assertThat(crawler.crawlNow(ACCOUNT_ID).status()).isEqualTo(CrawlResult.Status.SUCCEEDED);
    }

    @Test
    void accountWithoutPostsIsStillMarkedUpdated() {
        givenAccount(TOKEN, NOW_UTC.plusDays(30));
        when(postService.findPostRefs(USER_ID, Provider.FACEBOOK)).thenReturn(List.of());
        when(socialPlatformClients.get(Provider.FACEBOOK)).thenReturn(client);

        assertThat(crawler.crawlNow(ACCOUNT_ID)).isEqualTo(CrawlResult.succeeded(ACCOUNT_ID, 0));
        verifyNoInteractions(client);
        verify(socialMetricService).recordSnapshots(ACCOUNT_ID, List.of(), NOW_UTC, NOW_UTC);
    }

    @Test
    void expiredTokenIsSkippedWithoutCallingProvider() {
        givenAccount(TOKEN, NOW_UTC);

        assertThat(crawler.crawlNow(ACCOUNT_ID)).isEqualTo(CrawlResult.skipped(ACCOUNT_ID));
        verifyNoInteractions(postService, socialPlatformClients, socialMetricService);
    }

    @Test
    void unreadableTokenIsSkipped() {
        // AccessTokenConverter trả null khi không giải mã được (đổi key, dữ liệu hỏng)
        givenAccount(null, NOW_UTC.plusDays(30));

        assertThat(crawler.crawlNow(ACCOUNT_ID)).isEqualTo(CrawlResult.skipped(ACCOUNT_ID));
        verifyNoInteractions(postService, socialPlatformClients, socialMetricService);
    }

    @Test
    void deletedAccountIsSkipped() {
        when(socialAccountService.findCrawlAccount(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThat(crawler.crawlNow(ACCOUNT_ID)).isEqualTo(CrawlResult.skipped(ACCOUNT_ID));
        verifyNoInteractions(postService, socialMetricService);
    }

    @Test
    void tokenRejectedByProviderFailsWithoutSaving() {
        givenAccount(TOKEN, NOW_UTC.plusDays(30));
        givenPosts(new PostRef(1L, "a"));
        when(client.fetchMetrics(anyString(), anyList())).thenThrow(new InvalidAccessTokenException("revoked"));

        assertThat(crawler.crawlNow(ACCOUNT_ID)).isEqualTo(CrawlResult.failed(ACCOUNT_ID));
        verifyNoInteractions(socialMetricService);
    }

    @Test
    void failureInLaterBatchSavesNothing() {
        givenAccount(TOKEN, NOW_UTC.plusDays(30));
        givenPosts(new PostRef(1L, "a"), new PostRef(2L, "b"), new PostRef(3L, "c"));
        when(client.fetchMetrics(TOKEN, List.of("a", "b")))
                .thenReturn(List.of(metrics("a", 10), metrics("b", 20)));
        when(client.fetchMetrics(TOKEN, List.of("c"))).thenThrow(new SocialPlatformException("timeout"));

        assertThat(crawler.crawlNow(ACCOUNT_ID)).isEqualTo(CrawlResult.failed(ACCOUNT_ID));
        verify(socialMetricService, never()).recordSnapshots(any(), anyList(), any(), any());
    }

    @Test
    void saveFailureIsReportedAsFailed() {
        givenAccount(TOKEN, NOW_UTC.plusDays(30));
        givenPosts(new PostRef(1L, "a"));
        when(client.fetchMetrics(TOKEN, List.of("a"))).thenReturn(List.of(metrics("a", 10)));
        doThrow(new DataIntegrityViolationException("post deleted")).when(socialMetricService)
                .recordSnapshots(eq(ACCOUNT_ID), anyList(), any(), any());

        assertThat(crawler.crawlNow(ACCOUNT_ID)).isEqualTo(CrawlResult.failed(ACCOUNT_ID));
    }

    @Test
    void untrustedResultsAreFiltered() {
        givenAccount(TOKEN, NOW_UTC.plusDays(30));
        givenPosts(new PostRef(1L, "a"), new PostRef(2L, "b"));
        when(client.fetchMetrics(TOKEN, List.of("a", "b"))).thenReturn(List.of(
                metrics("a", 10),
                // Trùng bài đã có kết quả
                metrics("a", 99),
                // Bài không được hỏi (có thể của user khác)
                metrics("other-users-post", 5),
                // Số âm
                new PostMetrics("b", 1, -1, 0, 0)));

        assertThat(crawler.crawlNow(ACCOUNT_ID)).isEqualTo(CrawlResult.succeeded(ACCOUNT_ID, 1));
        ArgumentCaptor<List<CrawledMetric>> saved = ArgumentCaptor.captor();
        verify(socialMetricService).recordSnapshots(eq(ACCOUNT_ID), saved.capture(), any(), any());
        assertThat(saved.getValue()).containsExactly(new CrawledMetric(1L, 10, 1, 2, 100));
    }

    @Test
    void asyncEntryPointWrapsResultInCompletedFuture() {
        when(socialAccountService.findCrawlAccount(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThat(crawler.crawl(ACCOUNT_ID)).isCompletedWithValue(CrawlResult.skipped(ACCOUNT_ID));
    }

    private void givenAccount(String token, LocalDateTime expiresAtUtc) {
        when(socialAccountService.findCrawlAccount(ACCOUNT_ID)).thenReturn(Optional.of(
                new CrawlAccount(ACCOUNT_ID, USER_ID, Provider.FACEBOOK, token, expiresAtUtc)));
    }

    private void givenPosts(PostRef... posts) {
        when(postService.findPostRefs(USER_ID, Provider.FACEBOOK)).thenReturn(List.of(posts));
        when(socialPlatformClients.get(Provider.FACEBOOK)).thenReturn(client);
    }

    private static PostMetrics metrics(String externalId, long likes) {
        return new PostMetrics(externalId, likes, 1, 2, 100);
    }
}

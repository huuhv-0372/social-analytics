package com.huuhv.socialanalytics.scheduler;

import com.huuhv.socialanalytics.config.properties.AppProperties;
import com.huuhv.socialanalytics.service.AccountMetricsCrawler;
import com.huuhv.socialanalytics.service.CrawlResult;
import com.huuhv.socialanalytics.service.SocialAccountService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UpdateSocialMetricsJobTest {

    @Mock
    private SocialAccountService socialAccountService;

    @Mock
    private AccountMetricsCrawler accountMetricsCrawler;

    private UpdateSocialMetricsJob job;

    @BeforeEach
    void setUp() {
        job = jobWithTimeout(Duration.ofSeconds(5));
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void crawlsEveryAccountAndSummarizesResults() {
        when(socialAccountService.findAllAccountIds()).thenReturn(List.of(1L, 2L, 3L));
        when(accountMetricsCrawler.crawl(1L)).thenReturn(done(CrawlResult.succeeded(1L, 4)));
        when(accountMetricsCrawler.crawl(2L)).thenReturn(done(CrawlResult.skipped(2L)));
        when(accountMetricsCrawler.crawl(3L)).thenReturn(done(CrawlResult.succeeded(3L, 2)));

        assertThat(job.runOnce()).isEqualTo(new UpdateSocialMetricsJob.Summary(3, 2, 1, 0, 6));
    }

    @Test
    void failureOfOneAccountDoesNotStopOthers() {
        when(socialAccountService.findAllAccountIds()).thenReturn(List.of(1L, 2L, 3L));
        when(accountMetricsCrawler.crawl(1L)).thenReturn(done(CrawlResult.succeeded(1L, 1)));
        // Future lỗi bất ngờ (exception lọt khỏi task @Async)
        when(accountMetricsCrawler.crawl(2L)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException()));
        // Không gửi được task
        when(accountMetricsCrawler.crawl(3L)).thenThrow(new TaskRejectedException("executor shut down"));

        assertThat(job.runOnce()).isEqualTo(new UpdateSocialMetricsJob.Summary(3, 1, 0, 2, 1));
    }

    @Test
    void unfinishedAccountsAreCountedAsFailedAfterTimeout() {
        job = jobWithTimeout(Duration.ofMillis(100));
        when(socialAccountService.findAllAccountIds()).thenReturn(List.of(1L, 2L));
        when(accountMetricsCrawler.crawl(1L)).thenReturn(done(CrawlResult.succeeded(1L, 3)));
        when(accountMetricsCrawler.crawl(2L)).thenReturn(new CompletableFuture<>());

        assertThat(job.runOnce()).isEqualTo(new UpdateSocialMetricsJob.Summary(2, 1, 0, 1, 3));
    }

    @Test
    void jobIdIsInMdcWhileSubmittingAndClearedAfterwards() {
        AtomicReference<String> jobIdSeenByTask = new AtomicReference<>();
        when(socialAccountService.findAllAccountIds()).thenReturn(List.of(1L));
        when(accountMetricsCrawler.crawl(1L)).thenAnswer(invocation -> {
            jobIdSeenByTask.set(MDC.get("jobId"));
            return done(CrawlResult.succeeded(1L, 0));
        });

        job.runOnce();

        assertThat(jobIdSeenByTask.get()).isNotBlank().hasSize(8);
        assertThat(MDC.get("jobId")).isNull();
    }

    @Test
    void eachRunHasItsOwnJobId() {
        AtomicReference<String> first = new AtomicReference<>();
        when(socialAccountService.findAllAccountIds()).thenAnswer(invocation -> {
            if (first.get() == null) {
                first.set(MDC.get("jobId"));
            } else {
                assertThat(MDC.get("jobId")).isNotEqualTo(first.get());
            }
            return List.of();
        });

        job.runOnce();
        job.runOnce();
    }

    @Test
    void failureToListAccountsIsHandledAndMdcCleared() {
        when(socialAccountService.findAllAccountIds()).thenThrow(new DataAccessResourceFailureException("db down"));

        assertThat(job.runOnce()).isEqualTo(new UpdateSocialMetricsJob.Summary(0, 0, 0, 0, 0));
        assertThat(MDC.get("jobId")).isNull();
        verifyNoInteractions(accountMetricsCrawler);
    }

    @Test
    void noAccountsIsAnEmptyRun() {
        when(socialAccountService.findAllAccountIds()).thenReturn(List.of());

        assertThat(job.runOnce()).isEqualTo(new UpdateSocialMetricsJob.Summary(0, 0, 0, 0, 0));
    }

    private UpdateSocialMetricsJob jobWithTimeout(Duration timeout) {
        AppProperties properties = new AppProperties(null, null, null, null, null,
                new AppProperties.Jobs(new AppProperties.UpdateMetrics(100, timeout)));
        return new UpdateSocialMetricsJob(socialAccountService, accountMetricsCrawler, properties, Clock.systemUTC());
    }

    private static CompletableFuture<CrawlResult> done(CrawlResult result) {
        return CompletableFuture.completedFuture(result);
    }
}

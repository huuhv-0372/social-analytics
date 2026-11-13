package com.huuhv.socialanalytics.scheduler;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.huuhv.socialanalytics.config.properties.AppProperties;
import com.huuhv.socialanalytics.service.AccountMetricsCrawler;
import com.huuhv.socialanalytics.service.CrawlResult;
import com.huuhv.socialanalytics.service.SocialAccountService;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UpdateSocialMetricsJobTest {

    @Mock
    private SocialAccountService socialAccountService;

    @Mock
    private AccountMetricsCrawler accountMetricsCrawler;

    private static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(55);
    private static final Duration LOCK_AT_LEAST_FOR = Duration.ofMinutes(5);

    private UpdateSocialMetricsJob job;

    private final AtomicReference<LockConfiguration> requestedLock = new AtomicReference<>();
    private final AtomicBoolean unlocked = new AtomicBoolean();

    /** Mặc định luôn giành được khoá (1 instance) — ghi lại cấu hình khoá và việc nhả khoá. */
    private LockProvider lockProvider = configuration -> {
        requestedLock.set(configuration);
        return Optional.of(() -> unlocked.set(true));
    };

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
        when(accountMetricsCrawler.crawl(eq(1L), any())).thenReturn(done(CrawlResult.succeeded(1L, 4)));
        when(accountMetricsCrawler.crawl(eq(2L), any())).thenReturn(done(CrawlResult.skipped(2L)));
        when(accountMetricsCrawler.crawl(eq(3L), any())).thenReturn(done(CrawlResult.succeeded(3L, 2)));

        assertThat(job.runOnce()).isEqualTo(new UpdateSocialMetricsJob.Summary(3, 2, 1, 0, 6));
    }

    @Test
    void failureOfOneAccountDoesNotStopOthers() {
        when(socialAccountService.findAllAccountIds()).thenReturn(List.of(1L, 2L, 3L));
        when(accountMetricsCrawler.crawl(eq(1L), any())).thenReturn(done(CrawlResult.succeeded(1L, 1)));
        // Future lỗi bất ngờ (exception lọt khỏi task @Async)
        when(accountMetricsCrawler.crawl(eq(2L), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException()));
        // Không gửi được task
        when(accountMetricsCrawler.crawl(eq(3L), any())).thenThrow(new TaskRejectedException("executor shut down"));

        assertThat(job.runOnce()).isEqualTo(new UpdateSocialMetricsJob.Summary(3, 1, 0, 2, 1));
    }

    @Test
    void unfinishedAccountsAreCountedAsFailedAfterTimeout() {
        job = jobWithTimeout(Duration.ofMillis(100));
        when(socialAccountService.findAllAccountIds()).thenReturn(List.of(1L, 2L));
        when(accountMetricsCrawler.crawl(eq(1L), any())).thenReturn(done(CrawlResult.succeeded(1L, 3)));
        when(accountMetricsCrawler.crawl(eq(2L), any())).thenReturn(new CompletableFuture<>());

        assertThat(job.runOnce()).isEqualTo(new UpdateSocialMetricsJob.Summary(2, 1, 0, 1, 3));
    }

    @Test
    void timeoutAlsoCoversSubmissionAndRemainingAccountsAreNotStarted() {
        job = jobWithTimeout(Duration.ofMillis(100));
        when(socialAccountService.findAllAccountIds()).thenReturn(List.of(1L, 2L));
        // Gửi task bị chặn (hàng đợi đầy) tới quá hạn chót
        when(accountMetricsCrawler.crawl(eq(1L), any())).thenAnswer(invocation -> {
            Thread.sleep(150);
            return done(CrawlResult.succeeded(1L, 1));
        });

        assertThat(job.runOnce()).isEqualTo(new UpdateSocialMetricsJob.Summary(2, 1, 0, 1, 1));
        verify(accountMetricsCrawler, never()).crawl(eq(2L), any());
    }

    @Test
    void everyTaskReceivesDeadlineOfTheRun() {
        when(socialAccountService.findAllAccountIds()).thenReturn(List.of(1L, 2L));
        when(accountMetricsCrawler.crawl(any(), any())).thenReturn(done(CrawlResult.skipped(1L)));
        Instant before = Instant.now();

        job.runOnce();

        ArgumentCaptor<Instant> deadlines = ArgumentCaptor.captor();
        verify(accountMetricsCrawler, times(2)).crawl(any(), deadlines.capture());
        assertThat(deadlines.getAllValues()).hasSize(2).containsOnly(deadlines.getValue());
        assertThat(deadlines.getValue()).isBetween(before.plusSeconds(5), Instant.now().plusSeconds(5));
    }

    @Test
    void runIsSkippedWhileTasksOfPreviousRunAreStillRunning() {
        job = jobWithTimeout(Duration.ofMillis(100));
        CompletableFuture<CrawlResult> lateTask = new CompletableFuture<>();
        when(socialAccountService.findAllAccountIds()).thenReturn(List.of(1L));
        when(accountMetricsCrawler.crawl(eq(1L), any()))
                .thenReturn(lateTask)
                .thenReturn(done(CrawlResult.succeeded(1L, 2)));

        assertThat(job.runOnce()).isEqualTo(new UpdateSocialMetricsJob.Summary(1, 0, 0, 1, 0));
        // Task quá hạn vẫn đang chạy → lần sau không chồng lên
        assertThat(job.runOnce()).isEqualTo(new UpdateSocialMetricsJob.Summary(0, 0, 0, 0, 0));
        verify(socialAccountService, times(1)).findAllAccountIds();

        lateTask.complete(CrawlResult.failed(1L));
        assertThat(job.runOnce()).isEqualTo(new UpdateSocialMetricsJob.Summary(1, 1, 0, 0, 2));
    }

    @Test
    void startLogLineAlreadyHasJobId() {
        Logger jobLogger = (Logger) LoggerFactory.getLogger(UpdateSocialMetricsJob.class);
        Map<String, String> jobIdByMessage = new LinkedHashMap<>();
        AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                // Đọc MDC ngay lúc ghi log (sau runOnce MDC đã bị xoá)
                jobIdByMessage.put(event.getFormattedMessage(), String.valueOf(MDC.get("jobId")));
            }
        };
        appender.start();
        jobLogger.addAppender(appender);
        try {
            when(socialAccountService.findAllAccountIds()).thenReturn(List.of());

            job.runOnce();
        } finally {
            jobLogger.detachAppender(appender);
        }

        assertThat(jobIdByMessage.get("UPDATE SOCIAL METRICS JOB START")).hasSize(8)
                .isEqualTo(jobIdByMessage.get("UPDATE SOCIAL METRICS JOB END"));
    }

    @Test
    void jobIdIsInMdcWhileSubmittingAndClearedAfterwards() {
        AtomicReference<String> jobIdSeenByTask = new AtomicReference<>();
        when(socialAccountService.findAllAccountIds()).thenReturn(List.of(1L));
        when(accountMetricsCrawler.crawl(eq(1L), any())).thenAnswer(invocation -> {
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

    @Test
    void scheduledRunHoldsJobLockWithConfiguredDurations() {
        when(socialAccountService.findAllAccountIds()).thenReturn(List.of(1L));
        when(accountMetricsCrawler.crawl(eq(1L), any())).thenReturn(done(CrawlResult.succeeded(1L, 3)));

        assertThat(job.runExclusively()).contains(new UpdateSocialMetricsJob.Summary(1, 1, 0, 0, 3));

        LockConfiguration lock = requestedLock.get();
        assertThat(lock.getName()).isEqualTo(UpdateSocialMetricsJob.LOCK_NAME);
        assertThat(lock.getLockAtMostFor()).isEqualTo(LOCK_AT_MOST_FOR);
        assertThat(lock.getLockAtLeastFor()).isEqualTo(LOCK_AT_LEAST_FOR);
        assertThat(unlocked).isTrue();
    }

    @Test
    void scheduledRunIsSkippedWhileAnotherInstanceHoldsTheLock() {
        lockProvider = configuration -> Optional.empty();

        job.updateSocialMetricsJob();

        assertThat(job.runExclusively()).isEmpty();
        verifyNoInteractions(socialAccountService, accountMetricsCrawler);
    }

    @Test
    void lockFailureSkipsRunWithoutStoppingScheduler() {
        // Không lấy được khoá (mất kết nối DB) → không chạy (fail closed), không ném ra scheduler
        lockProvider = configuration -> {
            throw new DataAccessResourceFailureException("db down");
        };

        assertThat(job.runExclusively()).isEmpty();
        verifyNoInteractions(socialAccountService, accountMetricsCrawler);
    }

    private UpdateSocialMetricsJob jobWithTimeout(Duration timeout) {
        AppProperties properties = new AppProperties(null, null, null, null, null, new AppProperties.Jobs(
                new AppProperties.UpdateMetrics(100, timeout, LOCK_AT_MOST_FOR, LOCK_AT_LEAST_FOR), null), null);
        // Đọc lockProvider lúc gọi → test đổi được hành vi khoá sau khi tạo job
        LockProvider delegating = configuration -> lockProvider.lock(configuration);
        return new UpdateSocialMetricsJob(socialAccountService, accountMetricsCrawler, properties, Clock.systemUTC(),
                new DefaultLockingTaskExecutor(delegating));
    }

    private static CompletableFuture<CrawlResult> done(CrawlResult result) {
        return CompletableFuture.completedFuture(result);
    }
}

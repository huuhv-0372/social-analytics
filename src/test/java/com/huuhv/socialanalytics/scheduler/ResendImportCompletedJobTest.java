package com.huuhv.socialanalytics.scheduler;

import com.huuhv.socialanalytics.config.properties.AppProperties;
import com.huuhv.socialanalytics.service.ImportHistoryService;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResendImportCompletedJobTest {

    private static final Duration MIN_AGE = Duration.ofMinutes(10);
    private static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(9);
    private static final Duration LOCK_AT_LEAST_FOR = Duration.ofMinutes(1);

    @Mock
    private ImportHistoryService importHistoryService;

    private final AtomicReference<LockConfiguration> requestedLock = new AtomicReference<>();

    /** Mặc định luôn giành được khoá (1 instance). */
    private LockProvider lockProvider = configuration -> {
        requestedLock.set(configuration);
        return Optional.of(() -> {
        });
    };

    private ResendImportCompletedJob job;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties(null, null, null, null, null, new AppProperties.Jobs(null,
                new AppProperties.ResendImportCompleted(MIN_AGE, 50, LOCK_AT_MOST_FOR, LOCK_AT_LEAST_FOR)), null);
        LockProvider delegating = configuration -> lockProvider.lock(configuration);
        job = new ResendImportCompletedJob(importHistoryService, properties, Clock.systemUTC(),
                new DefaultLockingTaskExecutor(delegating));
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void republishesEveryStalePendingImport() {
        when(importHistoryService.findPendingIdsToResend(MIN_AGE, 50)).thenReturn(List.of(1L, 2L, 3L));
        when(importHistoryService.republishPending(1L)).thenReturn(true);
        // Đã được listener xử lý trong lúc chờ → không phát lại, không tính lỗi
        when(importHistoryService.republishPending(2L)).thenReturn(false);
        when(importHistoryService.republishPending(3L)).thenReturn(true);

        assertThat(job.runOnce()).isEqualTo(new ResendImportCompletedJob.Summary(3, 2, 0));
    }

    @Test
    void failureOfOneImportDoesNotStopOthers() {
        when(importHistoryService.findPendingIdsToResend(MIN_AGE, 50)).thenReturn(List.of(1L, 2L));
        when(importHistoryService.republishPending(1L)).thenThrow(new QueryTimeoutException("lock wait timeout"));
        when(importHistoryService.republishPending(2L)).thenReturn(true);

        assertThat(job.runOnce()).isEqualTo(new ResendImportCompletedJob.Summary(2, 1, 1));
        verify(importHistoryService).republishPending(2L);
    }

    @Test
    void nothingToResendDoesNothing() {
        when(importHistoryService.findPendingIdsToResend(MIN_AGE, 50)).thenReturn(List.of());

        assertThat(job.runOnce()).isEqualTo(new ResendImportCompletedJob.Summary(0, 0, 0));
        verify(importHistoryService, never()).republishPending(anyLong());
    }

    @Test
    void databaseFailureWhileListingIsHandledAndMdcCleared() {
        when(importHistoryService.findPendingIdsToResend(MIN_AGE, 50))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThat(job.runOnce()).isEqualTo(new ResendImportCompletedJob.Summary(0, 0, 0));
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void setsJobIdInMdcWhileRunningAndClearsItAfterwards() {
        AtomicReference<String> jobIdDuringRun = new AtomicReference<>();
        when(importHistoryService.findPendingIdsToResend(MIN_AGE, 50)).thenAnswer(invocation -> {
            jobIdDuringRun.set(MDC.get(UpdateSocialMetricsJob.MDC_JOB_ID));
            return List.of();
        });

        job.runOnce();

        assertThat(jobIdDuringRun.get()).hasSize(8);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void scheduledRunTakesSharedLockWithConfiguredDurations() {
        when(importHistoryService.findPendingIdsToResend(MIN_AGE, 50)).thenReturn(List.of());

        assertThat(job.runExclusively()).contains(new ResendImportCompletedJob.Summary(0, 0, 0));
        assertThat(requestedLock.get()).satisfies(lock -> {
            assertThat(lock.getName()).isEqualTo(ResendImportCompletedJob.LOCK_NAME);
            assertThat(lock.getLockAtMostFor()).isEqualTo(LOCK_AT_MOST_FOR);
            assertThat(lock.getLockAtLeastFor()).isEqualTo(LOCK_AT_LEAST_FOR);
        });
    }

    @Test
    void skipsWhenAnotherInstanceHoldsTheLock() {
        lockProvider = configuration -> Optional.empty();

        assertThat(job.runExclusively()).isEmpty();
        verifyNoInteractions(importHistoryService);
    }

    @Test
    void doesNotRunWhenLockCannotBeAcquired() {
        // Fail closed: mất kết nối DB lúc lấy khoá → không chạy, lịch sau thử lại
        lockProvider = configuration -> {
            throw new DataAccessResourceFailureException("db down");
        };

        assertThat(job.runExclusively()).isEmpty();
        verify(importHistoryService, never()).findPendingIdsToResend(any(), anyInt());
    }
}

package com.huuhv.socialanalytics.scheduler;

import com.huuhv.socialanalytics.config.properties.AppProperties;
import com.huuhv.socialanalytics.service.ImportHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Job định kỳ gửi lại message {@code IMPORT_COMPLETED} cho các lần import kẹt ở {@code PENDING}.
 * <p>
 * Vì sao cần: producer gửi message sau khi transaction import commit và <b>nuốt lỗi gửi</b> (import đã thành công, không
 * trả 500). Broker chết / mất mạng / quá {@code callTimeout} đúng lúc đó, hoặc broker embedded của dev (không lưu đĩa)
 * restart trước khi listener xử lý → message mất, import giữ {@code PENDING} và thống kê dashboard thiếu lần import đó.
 * Bảng {@code import_histories} có đủ dữ liệu dựng lại message, listener idempotent theo {@code importId} → gửi lại
 * (kể cả gửi trùng) an toàn.
 * <p>
 * Chỉ gửi lại {@code PENDING} lâu hơn {@code app.jobs.resend-import-completed.min-age}; mỗi lần gửi dời
 * {@code updated_at} nên broker chết lâu cũng chỉ thử lại mỗi {@code min-age}, không gửi dồn. Không gửi lại
 * {@code DEAD_LETTERED} (xem {@link ImportHistoryService#findPendingIdsToResend}).
 * <p>
 * Lịch, khoá ShedLock giữa các instance, MDC {@code jobId}: giống {@link UpdateSocialMetricsJob}. Job chỉ gọi Service.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResendImportCompletedJob {

    /** Tên khoá trong bảng {@code scheduler_locks}. */
    static final String LOCK_NAME = "resendImportCompletedJob";

    private static final Summary EMPTY = new Summary(0, 0, 0);

    private final ImportHistoryService importHistoryService;
    private final AppProperties appProperties;
    private final Clock clock;
    private final LockingTaskExecutor lockingTaskExecutor;

    /**
     * Tổng kết 1 lần chạy.
     *
     * @param pending     số import {@code PENDING} quá hạn tìm thấy
     * @param republished số import đã phát lại message (gửi lên broker lỗi thì producer log, lần sau gửi tiếp)
     * @param failed      số import lỗi khi phát lại (lỗi DB) — lần sau thử lại
     */
    public record Summary(int pending, int republished, int failed) {
    }

    @Scheduled(cron = "${app.jobs.resend-import-completed.cron:0 */10 * * * *}")
    public void resendImportCompletedJob() {
        runExclusively();
    }

    /**
     * Chạy 1 lần nếu giành được khoá chung giữa các instance.
     *
     * @return tổng kết; rỗng khi instance khác đang giữ khoá hoặc không lấy được khoá (đã log)
     */
    Optional<Summary> runExclusively() {
        AppProperties.ResendImportCompleted settings = appProperties.jobs().resendImportCompleted();
        LockConfiguration lockConfiguration = new LockConfiguration(clock.instant(), LOCK_NAME,
                settings.lockAtMostFor(), settings.lockAtLeastFor());
        LockingTaskExecutor.TaskWithResult<Summary> task = this::runOnce;
        try {
            LockingTaskExecutor.TaskResult<Summary> result = lockingTaskExecutor.executeWithLock(task, lockConfiguration);
            if (!result.wasExecuted()) {
                log.debug("Resend import-completed skipped: another instance holds the job lock");
                return Optional.empty();
            }
            return Optional.ofNullable(result.getResult());
        } catch (Error e) {
            throw e;
        } catch (Throwable e) {
            // runOnce tự xử lý lỗi của nó → lỗi tới đây là lỗi lấy/nhả khoá: không chạy (fail closed), lịch sau thử lại
            log.error("Resend import-completed skipped: job lock could not be acquired", e);
            return Optional.empty();
        }
    }

    /**
     * Chạy 1 lần (lịch hoặc gọi trực tiếp trong test). Lỗi 1 import không dừng các import còn lại.
     *
     * @return tổng kết; lỗi không lường trước (vd mất kết nối DB khi tìm danh sách) → mọi số liệu bằng 0, đã log
     */
    public Summary runOnce() {
        MDC.put(UpdateSocialMetricsJob.MDC_JOB_ID, UUID.randomUUID().toString().substring(0, 8));
        AppProperties.ResendImportCompleted settings = appProperties.jobs().resendImportCompleted();
        try {
            List<Long> importIds = importHistoryService.findPendingIdsToResend(settings.minAge(), settings.batchSize());
            if (importIds.isEmpty()) {
                log.debug("No stale PENDING import to resend");
                return EMPTY;
            }
            int republished = 0;
            int failed = 0;
            for (Long importId : importIds) {
                try {
                    if (importHistoryService.republishPending(importId)) {
                        republished++;
                    }
                } catch (RuntimeException e) {
                    failed++;
                    log.error("Import {}: could not republish import-completed message", importId, e);
                }
            }
            // WARN: import PENDING quá lâu nghĩa là message đã bị mất ở đâu đó — đáng để vận hành để ý (OWASP A09)
            log.warn("Republished import-completed for {} of {} imports PENDING longer than {} ({} failed)",
                    republished, importIds.size(), settings.minAge(), failed);
            return new Summary(importIds.size(), republished, failed);
        } catch (RuntimeException e) {
            log.error("Resend import-completed job failed", e);
            return EMPTY;
        } finally {
            MDC.clear();
        }
    }
}

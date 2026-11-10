package com.huuhv.socialanalytics.scheduler;

import com.huuhv.socialanalytics.config.properties.AppProperties;
import com.huuhv.socialanalytics.service.AccountMetricsCrawler;
import com.huuhv.socialanalytics.service.CrawlResult;
import com.huuhv.socialanalytics.service.SocialAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Job định kỳ cập nhật metrics cho mọi tài khoản mạng xã hội, mỗi tài khoản 1 task song song trên
 * {@code socialCrawlerExecutor} ({@link AccountMetricsCrawler}). Job chỉ điều phối và tổng kết, không có
 * {@code @Transactional}, chỉ gọi Service.
 * <ul>
 * <li>Lịch: {@code app.jobs.update-metrics.cron} (mặc định đầu mỗi giờ, {@code "-"} = tắt).</li>
 * <li>Mỗi lần chạy có {@code jobId} trong MDC (đặt trước dòng log đầu tiên); {@code MdcTaskDecorator} truyền sang
 * thread crawler.</li>
 * <li>Lỗi 1 tài khoản không dừng job: crawler tự xử lý lỗi, future lỗi bất ngờ được tính là {@code FAILED}.</li>
 * <li>Hạn chót = lúc bắt đầu + {@code app.jobs.update-metrics.timeout}, áp dụng cho cả lúc gửi task lẫn lúc chờ. Quá
 * hạn: tài khoản chưa gửi không được gửi nữa, tài khoản chưa xong tính {@code FAILED}; task đang chạy nhận cùng hạn
 * chót nên dừng ở bước kế tiếp và bỏ kết quả (không ghi DB). Executor chờ khi hàng đợi đầy, không bắt thread
 * scheduler chạy crawl thay → thời lượng lần chạy không vượt quá hạn chót đáng kể.</li>
 * <li>Không chồng lần chạy: task của lần trước chưa kết thúc hẳn (vd đang dở 1 lời gọi API sau khi quá hạn) → lần
 * chạy này bỏ qua. Lịch {@code @Scheduled} chạy tuần tự trên thread scheduler; chạy 1 instance — nhiều instance cần
 * khoá phân tán (vd ShedLock), ngoài phạm vi.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpdateSocialMetricsJob {

    static final String MDC_JOB_ID = "jobId";

    private static final Summary EMPTY = new Summary(0, 0, 0, 0, 0);

    private final SocialAccountService socialAccountService;
    private final AccountMetricsCrawler accountMetricsCrawler;
    private final AppProperties appProperties;
    private final Clock clock;

    /** Hoàn thành khi mọi task của lần chạy gần nhất đã thực sự kết thúc (kể cả task chạy quá hạn chót). */
    private volatile CompletableFuture<Void> previousRunTasks = CompletableFuture.completedFuture(null);

    /** Tổng kết 1 lần chạy. */
    public record Summary(int accounts, int succeeded, int skipped, int failed, int snapshots) {
    }

    @Scheduled(cron = "${app.jobs.update-metrics.cron:0 0 * * * *}")
    public void updateSocialMetricsJob() {
        runOnce();
    }

    /**
     * Chạy 1 lần (lịch hoặc gọi trực tiếp trong test).
     *
     * @return tổng kết; lỗi không lường trước (vd mất kết nối DB khi lấy danh sách) hoặc lần chạy trước chưa kết thúc
     *         → mọi số liệu bằng 0, đã log
     */
    public Summary runOnce() {
        MDC.put(MDC_JOB_ID, UUID.randomUUID().toString().substring(0, 8));
        log.info("UPDATE SOCIAL METRICS JOB START");
        Duration timeout = appProperties.jobs().updateMetrics().timeout();
        Instant startedAt = clock.instant();
        Instant deadline = startedAt.plus(timeout);
        try {
            if (!previousRunTasks.isDone()) {
                log.warn("Update social metrics skipped: crawl tasks of the previous run are still running");
                return EMPTY;
            }
            List<Long> accountIds = socialAccountService.findAllAccountIds();
            log.info("Update social metrics started for {} accounts", accountIds.size());

            List<CompletableFuture<CrawlResult>> futures = submitAll(accountIds, deadline, timeout);
            CompletableFuture<Void> allTasks = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
            previousRunTasks = allTasks;
            awaitAll(allTasks, deadline, timeout);
            Summary summary = summarize(accountIds, futures);

            log.info("Update social metrics finished in {} ms: {} succeeded, {} skipped, {} failed, {} snapshots",
                    Duration.between(startedAt, clock.instant()).toMillis(), summary.succeeded(), summary.skipped(),
                    summary.failed(), summary.snapshots());
            return summary;
        } catch (RuntimeException e) {
            // Xử lý tại đây để log còn jobId; scheduler vẫn chạy lần kế tiếp
            log.error("Update social metrics job failed", e);
            return EMPTY;
        } finally {
            log.info("UPDATE SOCIAL METRICS JOB END");
            MDC.clear();
        }
    }

    /** Gửi task theo thứ tự; quá hạn chót (vd chờ hàng đợi quá lâu) thì các tài khoản còn lại không được gửi. */
    private List<CompletableFuture<CrawlResult>> submitAll(List<Long> accountIds, Instant deadline, Duration timeout) {
        List<CompletableFuture<CrawlResult>> futures = new ArrayList<>(accountIds.size());
        int notStarted = 0;
        for (Long accountId : accountIds) {
            if (clock.instant().isBefore(deadline)) {
                futures.add(submit(accountId, deadline));
            } else {
                notStarted++;
                futures.add(CompletableFuture.completedFuture(CrawlResult.failed(accountId)));
            }
        }
        if (notStarted > 0) {
            log.error("Update social metrics timed out after {} while submitting; {} accounts were not started and are"
                    + " counted as failed", timeout, notStarted);
        }
        return futures;
    }

    private CompletableFuture<CrawlResult> submit(Long accountId, Instant deadline) {
        try {
            return accountMetricsCrawler.crawl(accountId, deadline)
                    .exceptionally(e -> {
                        log.error("Account {}: crawl task failed", accountId, e);
                        return CrawlResult.failed(accountId);
                    });
        } catch (RuntimeException e) {
            // Không gửi được task (vd executor đang tắt → TaskRejectedException) — không làm hỏng các tài khoản còn lại
            log.error("Account {}: crawl task could not be started", accountId, e);
            return CompletableFuture.completedFuture(CrawlResult.failed(accountId));
        }
    }

    private void awaitAll(CompletableFuture<Void> allTasks, Instant deadline, Duration timeout) {
        long remainingMillis = Math.max(0, Duration.between(clock.instant(), deadline).toMillis());
        try {
            allTasks.get(remainingMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.error("Update social metrics timed out after {}; unfinished accounts are counted as failed and their"
                    + " results are discarded", timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Update social metrics interrupted (application shutting down?)");
        } catch (ExecutionException e) {
            // Không xảy ra: mỗi future đã có exceptionally → luôn hoàn thành bình thường
            log.error("Unexpected failure while waiting for crawl tasks", e);
        }
    }

    private static Summary summarize(List<Long> accountIds, List<CompletableFuture<CrawlResult>> futures) {
        int succeeded = 0;
        int skipped = 0;
        int failed = 0;
        int snapshots = 0;
        for (int i = 0; i < futures.size(); i++) {
            CompletableFuture<CrawlResult> future = futures.get(i);
            CrawlResult result = future.isDone() && !future.isCompletedExceptionally()
                    ? future.join()
                    : CrawlResult.failed(accountIds.get(i));
            switch (result.status()) {
                case SUCCEEDED -> succeeded++;
                case SKIPPED -> skipped++;
                case FAILED -> failed++;
            }
            snapshots += result.snapshots();
        }
        return new Summary(accountIds.size(), succeeded, skipped, failed, snapshots);
    }
}

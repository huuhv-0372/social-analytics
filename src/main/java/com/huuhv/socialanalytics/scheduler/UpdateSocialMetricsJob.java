package com.huuhv.socialanalytics.scheduler;

import com.huuhv.socialanalytics.config.AppProperties;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Job định kỳ cập nhật metrics cho mọi tài khoản mạng xã hội, mỗi tài khoản 1
 * task song song trên
 * {@code socialCrawlerExecutor} ({@link AccountMetricsCrawler}). Job chỉ điều
 * phối và tổng kết, không có
 * {@code @Transactional}, chỉ gọi Service.
 * <ul>
 * <li>Lịch: {@code app.jobs.update-metrics.cron} (mặc định đầu mỗi giờ,
 * {@code "-"} = tắt).</li>
 * <li>Mỗi lần chạy có {@code jobId} trong MDC; {@code MdcTaskDecorator} truyền
 * sang thread crawler.</li>
 * <li>Lỗi 1 tài khoản không dừng job: crawler tự xử lý lỗi, future lỗi bất ngờ
 * được tính là {@code FAILED}.</li>
 * <li>Thread scheduler chờ các task xong (tối đa
 * {@code app.jobs.update-metrics.timeout}) nên lần chạy sau không
 * chồng lên lần trước. Chạy 1 instance; nhiều instance cần khoá phân tán (vd
 * ShedLock) — ngoài phạm vi.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpdateSocialMetricsJob {

    static final String MDC_JOB_ID = "jobId";

    private final SocialAccountService socialAccountService;
    private final AccountMetricsCrawler accountMetricsCrawler;
    private final AppProperties appProperties;
    private final Clock clock;

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
     * @return tổng kết; lỗi không lường trước (vd mất kết nối DB khi lấy danh sách)
     *         → mọi số liệu bằng 0, đã log
     */
    public Summary runOnce() {
        log.info("UPDATE SOCIAL METRICS JOB START");
        MDC.put(MDC_JOB_ID, UUID.randomUUID().toString().substring(0, 8));
        long startedAt = clock.millis();
        try {
            List<Long> accountIds = socialAccountService.findAllAccountIds();
            log.info("Update social metrics started for {} accounts", accountIds.size());

            List<CompletableFuture<CrawlResult>> futures = accountIds.stream().map(this::submit).toList();
            awaitAll(futures);
            Summary summary = summarize(accountIds, futures);

            log.info("Update social metrics finished in {} ms: {} succeeded, {} skipped, {} failed, {} snapshots",
                    clock.millis() - startedAt, summary.succeeded(), summary.skipped(), summary.failed(),
                    summary.snapshots());
            return summary;
        } catch (RuntimeException e) {
            // Xử lý tại đây để log còn jobId; scheduler vẫn chạy lần kế tiếp
            log.error("Update social metrics job failed", e);
            return new Summary(0, 0, 0, 0, 0);
        } finally {
            log.info("UPDATE SOCIAL METRICS JOB END");
            MDC.clear();
        }
    }

    private CompletableFuture<CrawlResult> submit(Long accountId) {
        try {
            return accountMetricsCrawler.crawl(accountId)
                    .exceptionally(e -> {
                        log.error("Account {}: crawl task failed", accountId, e);
                        return CrawlResult.failed(accountId);
                    });
        } catch (RuntimeException e) {
            // Không gửi được task (vd executor đang tắt) — không làm hỏng các tài khoản còn
            // lại
            log.error("Account {}: crawl task could not be started", accountId, e);
            return CompletableFuture.completedFuture(CrawlResult.failed(accountId));
        }
    }

    private void awaitAll(List<CompletableFuture<CrawlResult>> futures) {
        Duration timeout = appProperties.jobs().updateMetrics().timeout();
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.error("Update social metrics timed out after {}; unfinished accounts are counted as failed", timeout);
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

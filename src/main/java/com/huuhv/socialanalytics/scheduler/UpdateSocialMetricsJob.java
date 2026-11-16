package com.huuhv.socialanalytics.scheduler;

import com.huuhv.socialanalytics.config.properties.AppProperties;
import com.huuhv.socialanalytics.service.AccountMetricsCrawler;
import com.huuhv.socialanalytics.service.CrawlResult;
import com.huuhv.socialanalytics.service.DashboardEventService;
import com.huuhv.socialanalytics.service.SocialAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

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
 * scheduler chạy crawl thay → thời lượng lần chạy không vượt quá hạn chót đáng kể. Task được gửi theo thứ tự tài
 * khoản lâu chưa cập nhật nhất trước ({@code last_updated_at}, chưa từng cập nhật đứng đầu) → tài khoản bị bỏ vì quá
 * hạn ở lần này được làm đầu tiên ở lần sau, không tài khoản nào bị bỏ mãi.</li>
 * <li>Không chồng lần chạy: task của lần trước chưa kết thúc hẳn (vd đang dở 1 lời gọi API sau khi quá hạn) → lần
 * chạy này bỏ qua. Lịch {@code @Scheduled} chạy tuần tự trên thread scheduler.</li>
 * <li>Nhiều instance: lần chạy theo lịch chỉ thực hiện khi giành được khoá chung {@value #LOCK_NAME} (ShedLock trên
 * MySQL, {@code config/SchedulerLockConfig}) — instance khác cùng lịch bỏ qua. Khoá giữ tối đa
 * {@code lock-at-most-for} (instance chết thì khoá tự hết hạn trước lần chạy sau) và tối thiểu
 * {@code lock-at-least-for} (đồng hồ lệch vài giây không làm instance khác chạy lại). Lỗi lấy khoá (mất kết nối DB) →
 * không chạy (fail closed), lịch sau thử lại. {@link #runOnce()} gọi trực tiếp (test) không lấy khoá.</li>
 * <li>Lần chạy theo lịch có tài khoản crawl thành công → event {@code METRICS_UPDATED} cho dashboard của các chủ
 * tài khoản đó ({@link DashboardEventService}), phát <b>sau khi đã nhả khoá</b>: gửi JMS (broker chậm/chết chặn tới
 * connect + call timeout) không ăn vào thời hạn {@code lock-at-most-for}. Lỗi khi <b>nhả</b> khoá (job đã chạy xong)
 * vẫn phát event. Tài khoản qua bước kiểm hạn chót nhưng commit xong sau hạn (tính {@code FAILED} trong tổng kết) →
 * thêm 1 event khi các task chưa xong kết thúc. {@code jobId} bao cả lần chạy theo lịch (log khoá, log gửi event).
 * {@link #runOnce()} không phát event.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpdateSocialMetricsJob {

    static final String MDC_JOB_ID = "jobId";

    /** Tên khoá trong bảng {@code scheduler_locks} — đổi tên = khoá mới (không chặn được instance chạy bản cũ). */
    static final String LOCK_NAME = "updateSocialMetricsJob";

    private final SocialAccountService socialAccountService;
    private final AccountMetricsCrawler accountMetricsCrawler;
    private final DashboardEventService dashboardEventService;
    private final AppProperties appProperties;
    private final Clock clock;
    private final LockingTaskExecutor lockingTaskExecutor;

    /** Hoàn thành khi mọi task của lần chạy gần nhất đã thực sự kết thúc (kể cả task chạy quá hạn chót). */
    private volatile CompletableFuture<Void> previousRunTasks = CompletableFuture.completedFuture(null);

    /**
     * Tổng kết 1 lần chạy.
     *
     * @param updatedUserIds chủ các tài khoản crawl thành công (dashboard của họ cần làm mới)
     */
    public record Summary(int accounts, int succeeded, int skipped, int failed, int snapshots,
                          Set<Long> updatedUserIds) {

        public Summary {
            updatedUserIds = Set.copyOf(updatedUserIds);
        }
    }

    @Scheduled(cron = "${app.jobs.update-metrics.cron:0 0 * * * *}")
    public void updateSocialMetricsJob() {
        runExclusively();
    }

    /**
     * Chạy 1 lần nếu giành được khoá chung giữa các instance, rồi (khoá đã nhả) báo dashboard của các user vừa được
     * cập nhật. {@code jobId} có trong MDC suốt hàm (kể cả log lấy/nhả khoá và log gửi event).
     *
     * @return tổng kết; rỗng khi instance khác đang giữ khoá hoặc không lấy được khoá (đã log)
     */
    Optional<Summary> runExclusively() {
        MDC.put(MDC_JOB_ID, newJobId());
        try {
            Optional<Run> run = runWithLock();
            run.ifPresent(this::notifyUpdatedUsers);
            return run.map(Run::summary);
        } finally {
            MDC.clear();
        }
    }

    private Optional<Run> runWithLock() {
        AppProperties.UpdateMetrics settings = appProperties.jobs().updateMetrics();
        LockConfiguration lockConfiguration = new LockConfiguration(clock.instant(), LOCK_NAME,
                settings.lockAtMostFor(), settings.lockAtLeastFor());
        // Giữ kết quả ngoài lambda: lỗi khi NHẢ khoá được ném ra sau khi job đã chạy xong và ghi DB
        AtomicReference<Run> completedRun = new AtomicReference<>();
        LockingTaskExecutor.TaskWithResult<Run> task = () -> {
            Run run = runJob();
            completedRun.set(run);
            return run;
        };
        try {
            LockingTaskExecutor.TaskResult<Run> result = lockingTaskExecutor.executeWithLock(task, lockConfiguration);
            if (!result.wasExecuted()) {
                log.info("Update social metrics skipped: another instance holds the job lock");
                return Optional.empty();
            }
            return Optional.ofNullable(result.getResult());
        } catch (Error e) {
            throw e;
        } catch (Throwable e) {
            Run run = completedRun.get();
            if (run == null) {
                // runJob tự xử lý lỗi của nó → lỗi tới đây khi job chưa chạy là lỗi lấy khoá (vd mất kết nối DB):
                // không chạy (fail closed), scheduler vẫn chạy lần kế tiếp
                log.error("Update social metrics skipped: job lock could not be acquired", e);
                return Optional.empty();
            }
            // Job đã chạy xong (snapshot đã commit), khoá tự hết hạn sau lock-at-most-for → vẫn báo dashboard
            log.error("Update social metrics finished but the job lock could not be released", e);
            return Optional.of(run);
        }
    }

    /**
     * Event {@code METRICS_UPDATED} cho chủ các tài khoản thành công. Tài khoản ghi xong sau hạn chót (bị tính
     * {@code FAILED} trong tổng kết) được báo thêm 1 event khi mọi task chưa xong của lần chạy kết thúc.
     */
    private void notifyUpdatedUsers(Run run) {
        Set<Long> userIds = run.summary().updatedUserIds();
        if (!userIds.isEmpty()) {
            dashboardEventService.metricsUpdated(userIds);
        }
        // Chạy trên thread crawler hoàn tất task cuối (MdcTaskDecorator đã truyền jobId sang), hoặc ngay tại đây nếu
        // các task đã kết thúc
        run.lateUpdatedUserIds().thenAccept(lateUserIds -> {
            if (!lateUserIds.isEmpty()) {
                log.warn("Update social metrics: accounts of {} users were saved after the deadline; notifying their"
                        + " dashboards", lateUserIds.size());
                dashboardEventService.metricsUpdated(lateUserIds);
            }
        }).exceptionally(e -> {
            log.error("Failed to notify dashboards of accounts saved after the deadline", e);
            return null;
        });
    }

    /**
     * Chạy 1 lần, không lấy khoá, không phát event (gọi trực tiếp trong test).
     *
     * @return tổng kết; lỗi không lường trước (vd mất kết nối DB khi lấy danh sách) hoặc lần chạy trước chưa kết thúc
     *         → mọi số liệu bằng 0, đã log
     */
    public Summary runOnce() {
        MDC.put(MDC_JOB_ID, newJobId());
        try {
            return runJob().summary();
        } finally {
            MDC.clear();
        }
    }

    /** Thân 1 lần chạy; {@code jobId} đã có trong MDC (caller đặt và xoá). */
    private Run runJob() {
        log.info("UPDATE SOCIAL METRICS JOB START");
        Duration timeout = appProperties.jobs().updateMetrics().timeout();
        Instant startedAt = clock.instant();
        Instant deadline = startedAt.plus(timeout);
        try {
            if (!previousRunTasks.isDone()) {
                log.warn("Update social metrics skipped: crawl tasks of the previous run are still running");
                return Run.EMPTY;
            }
            List<Long> accountIds = socialAccountService.findAllAccountIds();
            log.info("Update social metrics started for {} accounts", accountIds.size());

            List<CompletableFuture<CrawlResult>> futures = submitAll(accountIds, deadline, timeout);
            CompletableFuture<Void> allTasks = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
            previousRunTasks = allTasks;
            awaitAll(allTasks, deadline, timeout);
            Run run = summarize(accountIds, futures);
            Summary summary = run.summary();

            log.info("Update social metrics finished in {} ms: {} succeeded, {} skipped, {} failed, {} snapshots",
                    Duration.between(startedAt, clock.instant()).toMillis(), summary.succeeded(), summary.skipped(),
                    summary.failed(), summary.snapshots());
            return run;
        } catch (RuntimeException e) {
            // Xử lý tại đây để log còn jobId; scheduler vẫn chạy lần kế tiếp
            log.error("Update social metrics job failed", e);
            return Run.EMPTY;
        } finally {
            log.info("UPDATE SOCIAL METRICS JOB END");
        }
    }

    private static String newJobId() {
        return UUID.randomUUID().toString().substring(0, 8);
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

    private static Run summarize(List<Long> accountIds, List<CompletableFuture<CrawlResult>> futures) {
        int succeeded = 0;
        int skipped = 0;
        int failed = 0;
        int snapshots = 0;
        // Tính theo "tài khoản thành công", không phải "có snapshot": tài khoản 0 bài vẫn đổi last_updated_at
        Set<Long> updatedUserIds = new HashSet<>();
        List<CompletableFuture<CrawlResult>> unfinished = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            CompletableFuture<CrawlResult> future = futures.get(i);
            boolean done = future.isDone();
            CrawlResult result = done && !future.isCompletedExceptionally()
                    ? future.join()
                    : CrawlResult.failed(accountIds.get(i));
            if (!done) {
                unfinished.add(future);
            }
            switch (result.status()) {
                case SUCCEEDED -> {
                    succeeded++;
                    updatedUserIds.add(result.userId());
                }
                case SKIPPED -> skipped++;
                case FAILED -> failed++;
            }
            snapshots += result.snapshots();
        }
        Summary summary = new Summary(accountIds.size(), succeeded, skipped, failed, snapshots, updatedUserIds);
        return new Run(summary, lateUpdatedUserIds(unfinished, summary.updatedUserIds()));
    }

    /**
     * Chủ các tài khoản chưa xong lúc hết hạn chót nhưng sau đó vẫn thành công (crawler đã qua bước kiểm hạn chót trước
     * khi ghi, commit xong muộn) — trừ user đã có trong tổng kết.
     */
    private static CompletableFuture<Set<Long>> lateUpdatedUserIds(List<CompletableFuture<CrawlResult>> unfinished,
                                                                   Set<Long> alreadyUpdated) {
        if (unfinished.isEmpty()) {
            return CompletableFuture.completedFuture(Set.of());
        }
        // Mỗi future đã có exceptionally → luôn hoàn thành bình thường
        return CompletableFuture.allOf(unfinished.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> unfinished.stream()
                        .map(CompletableFuture::join)
                        .filter(result -> result.status() == CrawlResult.Status.SUCCEEDED)
                        .map(CrawlResult::userId)
                        .filter(userId -> !alreadyUpdated.contains(userId))
                        .collect(Collectors.toUnmodifiableSet()));
    }

    /**
     * Kết quả nội bộ của 1 lần chạy (không lộ ra ngoài: {@link Summary} giữ nguyên là dữ liệu thuần để so sánh).
     *
     * @param lateUpdatedUserIds hoàn thành khi mọi task chưa xong lúc hết hạn chót đã kết thúc
     */
    private record Run(Summary summary, CompletableFuture<Set<Long>> lateUpdatedUserIds) {

        static final Run EMPTY = new Run(new Summary(0, 0, 0, 0, 0, Set.of()),
                CompletableFuture.completedFuture(Set.of()));
    }
}

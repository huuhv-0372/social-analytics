package com.huuhv.socialanalytics.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class WaitForQueueSpacePolicyTest {

    // 1 thread + hàng đợi 1 chỗ: task thứ 3 bị từ chối bởi pool → policy xử lý
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(1), new WaitForQueueSpacePolicy());

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void submitterWaitsForQueueSpaceAndTaskRunsOnPoolThread() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        executor.execute(() -> awaitQuietly(release));
        executor.execute(() -> awaitQuietly(release));
        AtomicReference<String> thirdTaskThread = new AtomicReference<>();
        String submitterThread = Thread.currentThread().getName();

        CompletableFuture<Void> submission = CompletableFuture.runAsync(() ->
                executor.execute(() -> thirdTaskThread.set(Thread.currentThread().getName())));

        // Pool + hàng đợi đầy → bên gửi chờ, không tự chạy task và không làm mất task
        Thread.sleep(WaitForQueueSpacePolicy.RECHECK_INTERVAL.toMillis() * 2);
        assertThat(submission).isNotDone();
        assertThat(thirdTaskThread.get()).isNull();

        release.countDown();
        submission.get(5, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(5)).until(() -> thirdTaskThread.get() != null);
        assertThat(thirdTaskThread.get()).isNotEqualTo(submitterThread).startsWith("pool-");
    }

    @Test
    void rejectsWhenExecutorIsShutDown() {
        executor.shutdown();

        // CallerRunsPolicy lặng lẽ bỏ task ở đây → future của @Async treo mãi; policy này báo lỗi cho bên gửi
        assertThatThrownBy(() -> executor.execute(() -> { }))
                .isInstanceOf(RejectedExecutionException.class);
    }

    @Test
    void waitingSubmitterIsRejectedWhenExecutorShutsDown() {
        CountDownLatch release = new CountDownLatch(1);
        executor.execute(() -> awaitQuietly(release));
        executor.execute(() -> awaitQuietly(release));

        CompletableFuture<Void> submission = CompletableFuture.runAsync(() -> executor.execute(() -> { }));
        executor.shutdown();
        try {
            assertThatThrownBy(() -> submission.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(RejectedExecutionException.class);
        } finally {
            release.countDown();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

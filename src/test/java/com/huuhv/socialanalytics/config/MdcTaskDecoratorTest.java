package com.huuhv.socialanalytics.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MdcTaskDecoratorTest {

    private final MdcTaskDecorator decorator = new MdcTaskDecorator();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void propagatesSubmitterMdcToWorkerThreadAndClearsItAfterTask() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            MDC.put("jobId", "job-1");
            AtomicReference<String> seenInTask = new AtomicReference<>();
            Runnable task = decorator.decorate(() -> seenInTask.set(MDC.get("jobId")));
            MDC.clear();

            executor.submit(task).get();
            // Cùng thread của pool chạy task tiếp theo: không còn jobId của task trước
            Future<String> leftover = executor.submit(() -> MDC.get("jobId"));

            assertThat(seenInTask).hasValue("job-1");
            assertThat(leftover.get()).isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void submitterWithoutMdcRunsTaskWithEmptyMdc() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // Thread của pool đang có MDC rác (vd task không qua decorator)
            executor.submit(() -> MDC.put("jobId", "stale")).get();
            AtomicReference<String> seenInTask = new AtomicReference<>("not-run");

            executor.submit(decorator.decorate(() -> seenInTask.set(MDC.get("jobId")))).get();

            assertThat(seenInTask.get()).isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void restoresCallerMdcWhenTaskRunsOnSubmittingThread() {
        // CallerRunsPolicy: task chạy ngay trên thread gửi — MDC của thread đó phải còn nguyên sau task
        MDC.put("jobId", "job-2");
        Runnable task = decorator.decorate(() -> MDC.put("jobId", "changed-inside-task"));

        task.run();

        assertThat(MDC.get("jobId")).isEqualTo("job-2");
    }
}

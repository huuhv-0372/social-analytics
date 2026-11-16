package com.huuhv.socialanalytics.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.huuhv.socialanalytics.config.properties.AppProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class AsyncConfigTest {

    // Base64 của 32 byte 0x01 — AppProperties bắt buộc có key hợp lệ
    private static final String VALID_KEY = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=";

    // TaskSchedulingAutoConfiguration: cung cấp ThreadPoolTaskSchedulerBuilder cho bean taskScheduler của AsyncConfig
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfig.class, AsyncConfig.class, ProbeConfig.class)
            .withPropertyValues("app.token-encryption.key=" + VALID_KEY,
                    "app.async.social-crawler.core-pool-size=2",
                    "app.async.social-crawler.max-pool-size=3",
                    "app.async.social-crawler.queue-capacity=5",
                    "app.async.social-crawler.await-termination=7s");

    private final Logger handlerLogger = (Logger) LoggerFactory.getLogger(LoggingAsyncUncaughtExceptionHandler.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void attachLogAppender() {
        logs.start();
        handlerLogger.addAppender(logs);
    }

    @AfterEach
    void detachLogAppender() {
        handlerLogger.detachAppender(logs);
        MDC.clear();
    }

    @Test
    void socialCrawlerExecutorIsConfiguredFromProperties() {
        contextRunner.run(context -> {
            ThreadPoolTaskExecutor executor =
                    context.getBean(AppProperties.SocialCrawler.EXECUTOR_BEAN_NAME, ThreadPoolTaskExecutor.class);
            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaxPoolSize()).isEqualTo(3);
            assertThat(executor.getQueueCapacity()).isEqualTo(5);
            // Thread rảnh tự dừng giữa 2 lần chạy job (job mỗi giờ)
            assertThat(executor.getThreadPoolExecutor().allowsCoreThreadTimeOut()).isTrue();
            assertThat(executor.getThreadNamePrefix()).isEqualTo("social-crawler-");
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(WaitForQueueSpacePolicy.class);
            assertThat(executor).extracting("waitForTasksToCompleteOnShutdown").isEqualTo(true);
            assertThat(executor).extracting("awaitTerminationMillis").isEqualTo(7000L);
        });
    }

    @Test
    void asyncMethodRunsOnSocialCrawlerThreadWithCallerMdc() {
        contextRunner.run(context -> {
            AsyncProbe probe = context.getBean(AsyncProbe.class);
            MDC.put("jobId", "job-42");

            List<String> threadAndJobId = probe.threadAndJobId().get(5, TimeUnit.SECONDS);

            assertThat(threadAndJobId.get(0)).startsWith("social-crawler-");
            assertThat(threadAndJobId.get(1)).isEqualTo("job-42");
        });
    }

    @Test
    void exceptionFromVoidAsyncMethodIsLoggedWithoutParameters() {
        contextRunner.run(context -> {
            context.getBean(AsyncProbe.class).fail("secret-access-token");

            await().atMost(Duration.ofSeconds(5)).until(() -> !logs.list.isEmpty());
            ILoggingEvent event = logs.list.getFirst();
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).contains("AsyncProbe.fail")
                    .doesNotContain("secret-access-token");
            assertThat(event.getThrowableProxy().getClassName()).isEqualTo(IllegalStateException.class.getName());
        });
    }

    /**
     * Cấu hình mặc định phải chạy đủ {@code max-pool-size} việc cùng lúc ngay khi hàng đợi còn trống —
     * {@code ThreadPoolExecutor} chỉ tạo thread vượt core khi queue đầy, nên core nhỏ hơn max sẽ chạy ít việc hơn.
     */
    @Test
    void defaultSettingsRunMaxPoolSizeTasksInParallelWhileQueueHasRoom() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withUserConfiguration(PropertiesConfig.class, AsyncConfig.class)
                .withPropertyValues("app.token-encryption.key=" + VALID_KEY)
                .run(context -> {
                    ThreadPoolTaskExecutor executor = context.getBean(
                            AppProperties.SocialCrawler.EXECUTOR_BEAN_NAME, ThreadPoolTaskExecutor.class);
                    int parallelism = executor.getMaxPoolSize();
                    CountDownLatch started = new CountDownLatch(parallelism);
                    CountDownLatch release = new CountDownLatch(1);
                    try {
                        for (int i = 0; i < parallelism; i++) {
                            executor.execute(() -> {
                                started.countDown();
                                awaitQuietly(release);
                            });
                        }

                        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                        assertThat(executor.getActiveCount()).isEqualTo(parallelism);
                        assertThat(executor.getQueueSize()).isZero();
                    } finally {
                        release.countDown();
                    }
                });
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * {@code @EnableWebSocketMessageBroker} đăng ký thêm 1 {@code TaskScheduler} ({@code messageBrokerTaskScheduler}) làm
     * Boot không tạo {@code taskScheduler}: {@code @Scheduled} vẫn phải chạy trên scheduler cấu hình bằng
     * {@code spring.task.scheduling.*}, không rơi sang pool của broker.
     */
    @Test
    void scheduledMethodsRunOnBootConfiguredSchedulerWhenBrokerSchedulerExists() {
        contextRunner
                .withUserConfiguration(BrokerSchedulerConfig.class, ScheduledProbeConfig.class)
                .withPropertyValues("spring.task.scheduling.thread-name-prefix=scheduler-")
                .run(context -> {
                    assertThat(context.getBean("taskScheduler", ThreadPoolTaskScheduler.class).getThreadNamePrefix())
                            .isEqualTo("scheduler-");

                    String thread = context.getBean(ScheduledProbe.class).thread.get(5, TimeUnit.SECONDS);

                    assertThat(thread).startsWith("scheduler-");
                });
    }

    @Test
    void failsStartupWhenCorePoolSizeExceedsMaxPoolSize() {
        contextRunner
                .withPropertyValues("app.async.social-crawler.core-pool-size=10",
                        "app.async.social-crawler.max-pool-size=2")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppProperties.class)
    static class PropertiesConfig {
    }

    @Configuration(proxyBeanMethods = false)
    static class ProbeConfig {
        @Bean
        AsyncProbe asyncProbe() {
            return new AsyncProbe();
        }
    }

    /** Giống bean do {@code @EnableWebSocketMessageBroker} đăng ký. */
    @Configuration(proxyBeanMethods = false)
    static class BrokerSchedulerConfig {
        @Bean
        ThreadPoolTaskScheduler messageBrokerTaskScheduler() {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setThreadNamePrefix("MessageBroker-");
            return scheduler;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ScheduledProbeConfig {
        @Bean
        ScheduledProbe scheduledProbe() {
            return new ScheduledProbe();
        }
    }

    static class ScheduledProbe {

        final CompletableFuture<String> thread = new CompletableFuture<>();

        @Scheduled(fixedDelay = 60_000)
        public void run() {
            thread.complete(Thread.currentThread().getName());
        }
    }

    static class AsyncProbe {

        @Async(AppProperties.SocialCrawler.EXECUTOR_BEAN_NAME)
        public CompletableFuture<List<String>> threadAndJobId() {
            return CompletableFuture.completedFuture(
                    List.of(Thread.currentThread().getName(), String.valueOf(MDC.get("jobId"))));
        }

        @Async(AppProperties.SocialCrawler.EXECUTOR_BEAN_NAME)
        public void fail(String accessToken) {
            throw new IllegalStateException("boom");
        }
    }
}

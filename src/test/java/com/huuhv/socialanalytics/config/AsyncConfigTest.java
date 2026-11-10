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
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class AsyncConfigTest {

    // Base64 của 32 byte 0x01 — AppProperties bắt buộc có key hợp lệ
    private static final String VALID_KEY = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
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
                    context.getBean(AsyncConfig.SOCIAL_CRAWLER_EXECUTOR, ThreadPoolTaskExecutor.class);
            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaxPoolSize()).isEqualTo(3);
            assertThat(executor.getQueueCapacity()).isEqualTo(5);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("social-crawler-");
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
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

    static class AsyncProbe {

        @Async(AsyncConfig.SOCIAL_CRAWLER_EXECUTOR)
        public CompletableFuture<List<String>> threadAndJobId() {
            return CompletableFuture.completedFuture(
                    List.of(Thread.currentThread().getName(), String.valueOf(MDC.get("jobId"))));
        }

        @Async(AsyncConfig.SOCIAL_CRAWLER_EXECUTOR)
        public void fail(String accessToken) {
            throw new IllegalStateException("boom");
        }
    }
}

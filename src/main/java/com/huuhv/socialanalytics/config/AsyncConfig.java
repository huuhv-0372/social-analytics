package com.huuhv.socialanalytics.config;

import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Bật {@code @Async} / {@code @Scheduled} (đặt ở config riêng, không ở Application class) và khai báo thread pool
 * {@value #SOCIAL_CRAWLER_EXECUTOR} cho việc crawl metrics song song. Gọi bằng
 * {@code @Async(AsyncConfig.SOCIAL_CRAWLER_EXECUTOR)}.
 * <ul>
 *     <li>{@link AsyncConfigurer} khai báo thành bean riêng, class này <b>không</b> implements nó: Boot bọc mọi bean
 *     {@code AsyncConfigurer} bằng proxy của nó, nếu class config vừa là {@code AsyncConfigurer} vừa có
 *     {@code @Bean} thì các factory method không gọi được trên proxy đó.</li>
 *     <li>Không override {@code getAsyncExecutor()}: {@code @Async} không ghi tên vẫn chạy trên
 *     {@code applicationTaskExecutor} của Boot (giữ lại nhờ {@code spring.task.execution.mode=force}, vì khai báo
 *     executor riêng làm Boot bỏ executor mặc định).</li>
 *     <li>Thread scheduler do Boot tạo ({@code spring.task.scheduling.*}).</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    public static final String SOCIAL_CRAWLER_EXECUTOR = "socialCrawlerExecutor";
    static final String SOCIAL_CRAWLER_THREAD_PREFIX = "social-crawler-";

    @Bean(name = SOCIAL_CRAWLER_EXECUTOR)
    ThreadPoolTaskExecutor socialCrawlerExecutor(AppProperties appProperties) {
        AppProperties.SocialCrawler settings = appProperties.async().socialCrawler();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(settings.corePoolSize());
        executor.setMaxPoolSize(settings.maxPoolSize());
        executor.setQueueCapacity(settings.queueCapacity());
        executor.setThreadNamePrefix(SOCIAL_CRAWLER_THREAD_PREFIX);
        // Hàng đợi đầy + đủ max thread → thread gửi task (scheduler) tự chạy task: chậm lại thay vì làm mất task
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setTaskDecorator(new MdcTaskDecorator());
        // Tắt app: chờ task đang crawl ghi xong (có giới hạn thời gian) thay vì ngắt giữa chừng
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationMillis(settings.awaitTermination().toMillis());
        return executor;
    }

    /** Exception thoát khỏi method {@code @Async void} → log (xem {@link LoggingAsyncUncaughtExceptionHandler}). */
    @Bean
    AsyncConfigurer asyncExceptionLoggingConfigurer() {
        return new AsyncConfigurer() {
            @Override
            public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
                return new LoggingAsyncUncaughtExceptionHandler();
            }
        };
    }
}

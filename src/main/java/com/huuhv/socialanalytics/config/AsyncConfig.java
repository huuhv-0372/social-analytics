package com.huuhv.socialanalytics.config;

import com.huuhv.socialanalytics.config.properties.AppProperties;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Bật {@code @Async} / {@code @Scheduled} (đặt ở config riêng, không ở Application class) và khai báo thread pool
 * {@value AppProperties.SocialCrawler#EXECUTOR_BEAN_NAME} cho việc crawl metrics song song. Gọi bằng
 * {@code @Async(AppProperties.SocialCrawler.EXECUTOR_BEAN_NAME)}.
 * <ul>
 *     <li>{@link AsyncConfigurer} khai báo thành bean riêng, class này <b>không</b> implements nó: Boot bọc mọi bean
 *     {@code AsyncConfigurer} bằng proxy của nó, nếu class config vừa là {@code AsyncConfigurer} vừa có
 *     {@code @Bean} thì các factory method không gọi được trên proxy đó.</li>
 *     <li>Không override {@code getAsyncExecutor()}: {@code @Async} không ghi tên vẫn chạy trên
 *     {@code applicationTaskExecutor} của Boot (giữ lại nhờ {@code spring.task.execution.mode=force}, vì khai báo
 *     executor riêng làm Boot bỏ executor mặc định).</li>
 *     <li>Scheduler cho {@code @Scheduled}: bean {@code taskScheduler} khai báo tại đây (xem {@link #taskScheduler}),
 *     vẫn dựng từ {@code spring.task.scheduling.*} của Boot.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    static final String SOCIAL_CRAWLER_THREAD_PREFIX = "social-crawler-";

    @Bean(name = AppProperties.SocialCrawler.EXECUTOR_BEAN_NAME)
    ThreadPoolTaskExecutor socialCrawlerExecutor(AppProperties appProperties) {
        AppProperties.SocialCrawler settings = appProperties.async().socialCrawler();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(settings.corePoolSize());
        executor.setMaxPoolSize(settings.maxPoolSize());
        executor.setQueueCapacity(settings.queueCapacity());
        // Job chạy mỗi giờ: core thread rảnh quá keep-alive (60s) tự dừng, không giữ 8 thread ngồi không cả giờ
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix(SOCIAL_CRAWLER_THREAD_PREFIX);
        // Hàng đợi đầy + đủ max thread → thread gửi task chờ tới khi có chỗ (không mất task, không tự chạy task);
        // executor đã tắt → TaskRejectedException
        executor.setRejectedExecutionHandler(new WaitForQueueSpacePolicy());
        executor.setTaskDecorator(new MdcTaskDecorator());
        // Tắt app: chờ task đang crawl ghi xong (có giới hạn thời gian) thay vì ngắt giữa chừng
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationMillis(settings.awaitTermination().toMillis());
        return executor;
    }

    /**
     * Scheduler chạy {@code @Scheduled} (job metrics, gửi lại IMPORT_COMPLETED) và task định kỳ của Spring Session.
     * Phải khai báo tường minh: {@code @EnableWebSocketMessageBroker} ({@link WebSocketConfig}) đăng ký bean
     * {@code TaskScheduler} {@code messageBrokerTaskScheduler}, làm cấu hình scheduler của Boot
     * ({@code @ConditionalOnMissingBean(TaskScheduler.class)}) không tạo {@code taskScheduler} — {@code @Scheduled} sẽ
     * lặng lẽ chạy trên pool {@code MessageBroker-} của broker (bỏ qua {@code spring.task.scheduling.*}, dừng theo vòng
     * đời broker). Tên bean {@code taskScheduler} là tên {@code ScheduledAnnotationBeanPostProcessor} chọn khi context
     * có nhiều {@code TaskScheduler}. Không {@code @Primary}: cấu hình WebSocket lấy scheduler của nó theo tên tham số,
     * primary sẽ đè lên.
     */
    @Bean(name = ScheduledAnnotationBeanPostProcessor.DEFAULT_TASK_SCHEDULER_BEAN_NAME)
    ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.build();
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

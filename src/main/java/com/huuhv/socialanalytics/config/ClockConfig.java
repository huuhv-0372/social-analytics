package com.huuhv.socialanalytics.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Nguồn thời gian dùng chung: inject {@link Clock} thay vì gọi {@code now()} trực tiếp để test cố định được thời gian.
 * Múi giờ theo JVM (biến môi trường {@code TZ} của container — {@code docker-compose.yaml} đặt từ {@code APP_TZ}, mặc
 * định {@code Asia/Ho_Chi_Minh}; image không đặt thì chạy UTC): quyết định ranh giới ngày của biểu đồ, giờ trong file
 * Excel và giờ hiển thị. DB luôn lưu UTC nên múi giờ này không ảnh hưởng dữ liệu đã lưu.
 */
@Slf4j
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        Clock clock = Clock.systemDefaultZone();
        // JVM nhận TZ không hợp lệ thì lặng lẽ dùng GMT → log để phát hiện cấu hình sai
        log.info("Server time zone: {}", clock.getZone());
        return clock;
    }
}

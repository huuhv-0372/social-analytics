package com.huuhv.socialanalytics.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Nguồn thời gian dùng chung: inject {@link Clock} thay vì gọi {@code now()} trực tiếp để test cố định được thời gian.
 * Múi giờ theo JVM (biến môi trường {@code TZ} của container).
 */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }
}

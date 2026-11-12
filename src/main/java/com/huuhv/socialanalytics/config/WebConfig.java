package com.huuhv.socialanalytics.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Cấu hình Spring MVC. Giới hạn {@code size} của tham số {@code Pageable} dùng cấu hình sẵn của Boot
 * ({@code spring.data.web.pageable.max-page-size} trong {@code application.yml}), không khai báo customizer riêng.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
}

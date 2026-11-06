package com.huuhv.socialanalytics.config;

import org.hibernate.validator.HibernateValidatorConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.validation.autoconfigure.ValidationConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.config.PageableHandlerMethodArgumentResolverCustomizer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

// Trùng với @ConfigurationPropertiesScan khi chạy app (vô hại), nhưng bắt buộc cho test slice:
// @WebMvcTest không chạy @ConfigurationPropertiesScan nên @Import(WebConfig.class) cần tự đăng ký AppProperties
@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class WebConfig implements WebMvcConfigurer {

    /** Múi giờ đi trước UTC xa nhất (Line Islands, UTC+14). */
    private static final ZoneOffset MAX_CLIENT_OFFSET = ZoneOffset.ofHours(14);

    /**
     * Tham số {@code Pageable} của controller theo {@code app.pagination.*}: {@code size} vượt max bị hạ xuống max
     * (OWASP A06). Bean này thay customizer mặc định của Boot ({@code spring.data.web.pageable.*}).
     */
    @Bean
    PageableHandlerMethodArgumentResolverCustomizer pageableCustomizer(AppProperties appProperties) {
        AppProperties.Pagination pagination = appProperties.pagination();
        return resolver -> {
            resolver.setMaxPageSize(pagination.maxSize());
            resolver.setFallbackPageable(PageRequest.of(0, pagination.defaultSize()));
        };
    }

    /**
     * {@code LocalDateTime} không có múi giờ: client gửi giờ địa phương, còn {@code @Past}/{@code @PastOrPresent}
     * so với đồng hồ theo múi giờ của server. Nới sai số bằng chênh lệch giữa múi giờ xa nhất (UTC+14) và múi giờ
     * server, để "bây giờ" của mọi client đều hợp lệ trong khi giá trị thực sự ở tương lai vẫn bị chặn.
     */
    @Bean
    ValidationConfigurationCustomizer temporalToleranceCustomizer() {
        ZoneOffset serverOffset = ZoneId.systemDefault().getRules().getOffset(Instant.now());
        Duration tolerance = Duration.ofSeconds(
                Math.max(0, MAX_CLIENT_OFFSET.getTotalSeconds() - serverOffset.getTotalSeconds()));
        return configuration -> configuration.addProperty(
                HibernateValidatorConfiguration.TEMPORAL_VALIDATION_TOLERANCE, String.valueOf(tolerance.toMillis()));
    }
}

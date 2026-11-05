package com.huuhv.socialanalytics.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.config.PageableHandlerMethodArgumentResolverCustomizer;

// Trùng với @ConfigurationPropertiesScan khi chạy app (vô hại), nhưng bắt buộc cho test slice:
// @WebMvcTest không chạy @ConfigurationPropertiesScan nên @Import(WebConfig.class) cần tự đăng ký AppProperties
@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class WebConfig {

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
}

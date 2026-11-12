package com.huuhv.socialanalytics.config;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

/**
 * Session HTTP lưu MySQL qua Spring Session JDBC (bảng {@code SPRING_SESSION}, Flyway {@code V3_1}): mọi instance sau
 * load balancer dùng chung state/PKCE của luồng OAuth2 và {@code SecurityContext}; restart không đăng xuất user.
 * Boot tự cấu hình repository (timeout lấy từ {@code server.servlet.session.timeout}, cookie {@code SESSION} theo
 * {@code server.servlet.session.cookie.*}); ở đây chỉ thay cách deserialize attribute
 * ({@link SessionAttributeSerialization} — whitelist class, OWASP A08).
 */
@Configuration(proxyBeanMethods = false)
public class SessionConfig {

    @Bean
    SessionRepositoryCustomizer<JdbcIndexedSessionRepository> sessionAttributeSerializationCustomizer(
            ConfigurableListableBeanFactory beanFactory) {
        // Customizer chạy sau khi Spring Session gán conversion service mặc định → ghi đè được
        return repository -> repository.setConversionService(
                SessionAttributeSerialization.conversionService(beanFactory.getBeanClassLoader()));
    }
}

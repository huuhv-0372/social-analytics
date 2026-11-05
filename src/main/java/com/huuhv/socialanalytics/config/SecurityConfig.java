package com.huuhv.socialanalytics.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * TẠM THỜI (Task 1.4 → thay ở Task 3.1): mở {@code /api/**} và Swagger để thử API trước khi có OAuth2.
 * <ul>
 *     <li>{@code /api/**} permitAll + bỏ CSRF; danh tính gửi qua HTTP Basic (user cấu hình ở {@code spring.security.user.*}).
 *     Request ẩn danh vẫn bị Service từ chối với 401 vì không xác định được owner (fail closed).</li>
 *     <li>Mọi đường dẫn khác vẫn deny-by-default.</li>
 * </ul>
 * Task 3.1 phải: bỏ permitAll {@code /api/**}, bật lại CSRF, không public Swagger ở production, thêm security headers.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/**").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated())
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
                .httpBasic(withDefaults())
                .formLogin(withDefaults());
        return http.build();
    }
}

package com.huuhv.socialanalytics.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.SecurityProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * TẠM THỜI (Task 1.4 → thay ở Task 3.1): mở {@code /api/**} và Swagger để thử API trước khi có OAuth2.
 * <ul>
 *     <li>{@code /api/**} permitAll; danh tính gửi qua HTTP Basic (user cấu hình ở {@code spring.security.user.*}).
 *     Request ẩn danh vẫn bị Service từ chối với 401 vì không xác định được owner (fail closed).</li>
 *     <li>CSRF bật cho mọi request ghi (kể cả {@code /api/**}): token nằm trong cookie {@code XSRF-TOKEN},
 *     client gửi lại qua header {@code X-XSRF-TOKEN} (Swagger UI tự làm qua {@code springdoc.swagger-ui.csrf.enabled}).</li>
 *     <li>Mọi đường dẫn khác vẫn deny-by-default.</li>
 * </ul>
 * Task 3.1 phải: bỏ permitAll {@code /api/**}, bỏ user HTTP Basic, không public Swagger ở production, thêm security headers.
 */
@Configuration
@EnableWebSecurity
// Đăng ký tường minh: test slice (@WebMvcTest) của Boot 4 không tự tạo bean SecurityProperties
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    private static final Pattern ENCODED_PASSWORD = Pattern.compile("^\\{.+}.*$");

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/**").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated())
                .csrf(csrf -> csrf.spa())
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .httpBasic(withDefaults());
        return http.build();
    }

    /**
     * Token CSRF được tải trễ (deferred) nên cookie {@code XSRF-TOKEN} chỉ được ghi khi có code đọc token.
     * Filter này đọc token ở mọi request để client (Swagger UI, Postman) luôn nhận được cookie trước request ghi đầu tiên.
     */
    private static final class CsrfCookieFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                throws ServletException, IOException {
            CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (csrfToken != null) {
                csrfToken.getToken();
            }
            filterChain.doFilter(request, response);
        }
    }

    /**
     * TẠM THỜI tới Task 3.1: user HTTP Basic từ {@code spring.security.user.*}. Khai báo thay auto-config của Boot để
     * thiếu mật khẩu thì dừng khởi động, không để Boot sinh mật khẩu ngẫu nhiên và in ra log (OWASP A02/A09).
     */
    @Bean
    UserDetailsService apiUserDetailsService(SecurityProperties securityProperties) {
        SecurityProperties.User user = securityProperties.getUser();
        if (user.isPasswordGenerated()) {
            throw new IllegalStateException("API_USER_PASSWORD must be set (spring.security.user.password)");
        }
        // Giá trị đã hash sẵn có tiền tố {id} (vd {bcrypt}$2a$...) dùng nguyên như Boot mặc định, không encode lần 2
        String password = ENCODED_PASSWORD.matcher(user.getPassword()).matches()
                ? user.getPassword()
                : PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(user.getPassword());
        return new InMemoryUserDetailsManager(User.withUsername(user.getName())
                .password(password)
                .roles(user.getRoles().toArray(String[]::new))
                .build());
    }
}

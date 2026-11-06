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
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.authentication.logout.SimpleUrlLogoutSuccessHandler;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Cấu hình Spring Security (OWASP A01/A02):
 * <ul>
 *     <li><b>Deny-by-default</b>: chỉ {@code /login}, {@code /oauth2/**}, {@code /error} và static resource là public;
 *     mọi đường dẫn khác (kể cả {@code /api/**}, Swagger) phải đăng nhập.</li>
 *     <li>Chưa đăng nhập: REST ({@code /api/**}, {@code /v3/api-docs/**}) nhận 401 {@code ProblemDetail};
 *     trang web được redirect về {@code /login} (Swagger UI tạm thời nhận 401 Basic — xem {@link #restRequestMatcher()}).</li>
 *     <li>CSRF bật cho mọi request ghi: token nằm trong cookie {@code XSRF-TOKEN}, client gửi lại qua header
 *     {@code X-XSRF-TOKEN} (Swagger UI tự làm qua {@code springdoc.swagger-ui.csrf.enabled}); trang Thymeleaf đọc
 *     token từ {@code <meta name="_csrf">} / form {@code th:action} chèn hidden {@code _csrf}.</li>
 *     <li>Đăng nhập OAuth2 (Facebook): {@code /oauth2/authorization/{registrationId}} → callback
 *     {@code /login/oauth2/code/{registrationId}} → luôn redirect {@code /dashboard}; lỗi → {@code /login?error}.</li>
 *     <li>Logout chỉ nhận POST (có CSRF), redirect cố định về {@code /login?logout} (không open redirect).</li>
 *     <li>Security header: CSP, {@code X-Content-Type-Options}, {@code Referrer-Policy}, {@code frame-ancestors 'none'}
 *     + {@code X-Frame-Options: DENY}; HSTS mặc định của Spring (chỉ gửi qua HTTPS).</li>
 * </ul>
 * TẠM THỜI (tới Task 3.4): song song với OAuth2 vẫn giữ HTTP Basic (user ở {@code spring.security.user.*}) để gọi API,
 * vì user đăng nhập OAuth2 chưa được map sang {@code User} trong DB. Task 3.4 gỡ {@code httpBasic} và
 * {@link #apiUserDetailsService}.
 */
@Configuration
@EnableWebSecurity
// Đăng ký tường minh: test slice (@WebMvcTest) của Boot 4 không tự tạo bean SecurityProperties
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    /**
     * Chỉ cho phép tài nguyên từ chính app và CDN đang dùng (jsDelivr, đã pin version + SRI).
     * Không có {@code 'unsafe-inline'}: script/style riêng đặt trong {@code static/}.
     */
    static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self' https://cdn.jsdelivr.net",
            "style-src 'self' https://cdn.jsdelivr.net",
            "img-src 'self' data:",
            "font-src 'self' https://cdn.jsdelivr.net",
            "connect-src 'self'",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    static final String LOGIN_PAGE = "/login";
    static final String LOGOUT_SUCCESS_URL = LOGIN_PAGE + "?logout";
    static final String LOGIN_SUCCESS_URL = "/dashboard";
    static final String LOGIN_FAILURE_URL = LOGIN_PAGE + "?error";

    private static final Pattern ENCODED_PASSWORD = Pattern.compile("^\\{.+}.*$");

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        ApiAuthenticationEntryPoint apiEntryPoint = new ApiAuthenticationEntryPoint();
        OAuth2LoginResultHandler loginResultHandler = new OAuth2LoginResultHandler(LOGIN_SUCCESS_URL, LOGIN_FAILURE_URL);
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(LOGIN_PAGE, "/oauth2/**", "/error").permitAll()
                        .requestMatchers("/css/**", "/js/**", "/images/**", "/favicon.ico").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        // Thứ tự quan trọng: REST khớp trước, còn lại (trang web) redirect về trang login
                        .defaultAuthenticationEntryPointFor(apiEntryPoint, restRequestMatcher())
                        .defaultAuthenticationEntryPointFor(
                                new LoginUrlAuthenticationEntryPoint(LOGIN_PAGE), AnyRequestMatcher.INSTANCE)
                        .accessDeniedHandler(new LoggingAccessDeniedHandler()))
                // Như csrf.spa() (cookie XSRF-TOKEN đọc được bằng JS) nhưng header nhận cả token đã mask từ <meta>
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfHeaderOrFormTokenRequestHandler()))
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        // Handler tường minh: luôn redirect URL nội bộ cố định (không theo tham số → không open redirect),
                        // không để httpBasic chèn nhánh 204 cho request không phải HTML
                        .logoutSuccessHandler(logoutSuccessHandler())
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID"))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
                // Social login: trang login riêng (không dùng trang mặc định của Spring); state + PKCE do Spring xử lý.
                // Entry point mặc định của oauth2Login không được dùng vì 2 entry point khai báo ở trên khớp trước.
                .oauth2Login(oauth2 -> oauth2
                        .loginPage(LOGIN_PAGE)
                        .successHandler(loginResultHandler)
                        .failureHandler(loginResultHandler))
                // TẠM THỜI tới Task 3.4: sai thông tin đăng nhập Basic cũng trả 401 ProblemDetail như request ẩn danh
                .httpBasic(basic -> basic.authenticationEntryPoint(apiEntryPoint));
        return http.build();
    }

    private static LogoutSuccessHandler logoutSuccessHandler() {
        SimpleUrlLogoutSuccessHandler handler = new SimpleUrlLogoutSuccessHandler();
        handler.setDefaultTargetUrl(LOGOUT_SUCCESS_URL);
        handler.setAlwaysUseDefaultTargetUrl(true);
        return handler;
    }

    private static RequestMatcher restRequestMatcher() {
        PathPatternRequestMatcher.Builder path = PathPatternRequestMatcher.withDefaults();
        return new OrRequestMatcher(path.matcher("/api/**"), path.matcher("/v3/api-docs/**"),
                // TẠM THỜI tới Task 3.4: trang /login chưa có cách đăng nhập → Swagger UI nhận 401 Basic để trình duyệt
                // hiện hộp thoại đăng nhập. Khi có OAuth2, Swagger UI redirect về /login như mọi trang web khác.
                path.matcher("/swagger-ui.html"), path.matcher("/swagger-ui/**"));
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
     * TẠM THỜI tới Task 3.4: user HTTP Basic từ {@code spring.security.user.*}. Khai báo thay auto-config của Boot để
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

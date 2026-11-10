package com.huuhv.socialanalytics.config;

import com.huuhv.socialanalytics.service.CurrentUserService;
import com.huuhv.socialanalytics.service.CustomOAuth2UserService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.authentication.logout.SimpleUrlLogoutSuccessHandler;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Cấu hình Spring Security (OWASP A01/A02):
 * <ul>
 *     <li><b>Deny-by-default</b>: chỉ {@code /login}, {@code /oauth2/**}, {@code /error} và static resource là public;
 *     mọi đường dẫn khác (kể cả {@code /api/**}, Swagger) phải đăng nhập. Dispatch {@code ERROR} (trang lỗi) luôn được
 *     cho qua để giữ đúng mã lỗi gốc.</li>
 *     <li>Chưa đăng nhập: REST ({@code /api/**}, {@code /v3/api-docs/**}) nhận 401 {@code ProblemDetail};
 *     trang web (kể cả Swagger UI) được redirect về {@code /login}. Bị từ chối (403, gồm lỗi CSRF): REST nhận
 *     {@code ProblemDetail}, trang web nhận trang lỗi 403.</li>
 *     <li>CSRF bật cho mọi request ghi: token nằm trong cookie {@code XSRF-TOKEN}, client gửi lại qua header
 *     {@code X-XSRF-TOKEN} (Swagger UI tự làm qua {@code springdoc.swagger-ui.csrf.enabled}); trang Thymeleaf đọc
 *     token từ {@code <meta name="_csrf">} / form {@code th:action} chèn hidden {@code _csrf}.</li>
 *     <li>Đăng nhập OAuth2 (Facebook, X): {@code /oauth2/authorization/{registrationId}} → callback
 *     {@code /login/oauth2/code/{registrationId}} → luôn redirect {@code /dashboard}; lỗi → {@code /login?error}.</li>
 *     <li>Logout chỉ nhận POST (có CSRF), redirect cố định về {@code /login?logout} (không open redirect).</li>
 *     <li>Session đã đăng nhập nhưng user không còn trong DB bị huỷ ({@link StaleSessionFilter}) → xử lý như ẩn
 *     danh.</li>
 *     <li>Security header: CSP, {@code X-Content-Type-Options}, {@code Referrer-Policy}, {@code frame-ancestors 'none'}
 *     + {@code X-Frame-Options: DENY}; HSTS mặc định của Spring (chỉ gửi qua HTTPS).</li>
 * </ul>
 * Chỉ có đăng nhập OAuth2: không có HTTP Basic / form login / user mặc định của Boot. Gọi API (Swagger UI, Postman)
 * dùng session cookie sau khi đăng nhập trên trình duyệt.
 */
@Configuration
@EnableWebSecurity
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

    static final List<String> STATIC_RESOURCE_PATTERNS = List.of("/css/**", "/js/**", "/images/**", "/favicon.ico");

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, CustomOAuth2UserService oauth2UserService,
                                            CurrentUserService currentUserService) throws Exception {
        ApiAuthenticationEntryPoint apiEntryPoint = new ApiAuthenticationEntryPoint();
        RequestMatcher restRequestMatcher = restRequestMatcher();
        OAuth2LoginResultHandler loginResultHandler = new OAuth2LoginResultHandler(LOGIN_SUCCESS_URL, LOGIN_FAILURE_URL);
        http
                .authorizeHttpRequests(auth -> auth
                        // Lỗi gửi bằng sendError (vd 403 CSRF, 404) được Tomcat dispatch lại sang trang lỗi và chain
                        // chạy lại cho lượt đó: cho qua mọi dispatch ERROR (không phụ thuộc server.error.path) để giữ
                        // mã lỗi gốc, không bị anyRequest().authenticated() đè thành 401 / redirect login.
                        // Request gốc đã qua kiểm tra quyền ở dispatch REQUEST nên không mở thêm quyền truy cập nào.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(LOGIN_PAGE, "/oauth2/**", "/error").permitAll()
                        .requestMatchers(STATIC_RESOURCE_PATTERNS.toArray(String[]::new)).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        // Thứ tự quan trọng: REST khớp trước, còn lại (trang web) redirect về trang login
                        .defaultAuthenticationEntryPointFor(apiEntryPoint, restRequestMatcher)
                        .defaultAuthenticationEntryPointFor(
                                new LoginUrlAuthenticationEntryPoint(LOGIN_PAGE), AnyRequestMatcher.INSTANCE)
                        // 403: REST nhận ProblemDetail, trang web dùng trang lỗi mặc định; cả hai đều được log
                        .accessDeniedHandler(new LoggingAccessDeniedHandler(restRequestMatcher)))
                // Như csrf.spa() (cookie XSRF-TOKEN đọc được bằng JS) nhưng header nhận cả token đã mask từ <meta>
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfHeaderOrFormTokenRequestHandler()))
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                // Session đã đăng nhập nhưng user không còn trong DB → logout rồi xử lý như request ẩn danh
                .addFilterBefore(new StaleSessionFilter(currentUserService), AnonymousAuthenticationFilter.class)
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        // Handler tường minh: luôn redirect URL nội bộ cố định (không theo tham số → không open redirect),
                        // không phụ thuộc loại request (Accept)
                        .logoutSuccessHandler(logoutSuccessHandler())
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID"))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
                // Social login: trang login riêng (không dùng trang mặc định của Spring). state + PKCE (S256) do Spring
                // xử lý — Spring Security 7 mặc định requireProofKey=true cho mọi client, kể cả confidential client.
                // Entry point mặc định của oauth2Login không được dùng vì 2 entry point khai báo ở trên khớp trước.
                .oauth2Login(oauth2 -> oauth2
                        .loginPage(LOGIN_PAGE)
                        // Map user info theo provider (X bọc thông tin trong "data")
                        .userInfoEndpoint(userInfo -> userInfo.userService(oauth2UserService))
                        .successHandler(loginResultHandler)
                        .failureHandler(loginResultHandler));
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
        return new OrRequestMatcher(path.matcher("/api/**"), path.matcher("/v3/api-docs/**"));
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
}

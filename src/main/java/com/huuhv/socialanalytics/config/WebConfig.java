package com.huuhv.socialanalytics.config;

import com.huuhv.socialanalytics.service.CurrentUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Cấu hình Spring MVC. Giới hạn {@code size} của tham số {@code Pageable} dùng cấu hình sẵn của Boot
 * ({@code spring.data.web.pageable.max-page-size} trong {@code application.yml}), không khai báo customizer riêng.
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final CurrentUserService currentUserService;

    /**
     * TẠM THỜI tới Task 3.1: tạo {@code User} cho principal HTTP Basic ngay khi request vào {@code /api/**},
     * <b>trước</b> khi Service mở transaction. Nếu tạo bên trong transaction của Service, mỗi request lần đầu cần
     * giữ 2 connection cùng lúc và pool có thể deadlock khi nhiều request đồng thời. Task 3.1 chuyển việc này sang
     * bước đăng nhập OAuth2 ({@code CustomOAuth2UserService}).
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                currentUserService.provisionCurrentUserIfMissing();
                return true;
            }
        }).addPathPatterns("/api/**");
    }
}

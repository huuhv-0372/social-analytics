package com.huuhv.socialanalytics.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Xử lý exception nghiệp vụ của các trang Thymeleaf ({@code controller/web}). REST API dùng {@link ApiExceptionHandler}.
 */
@Slf4j
@ControllerAdvice(basePackages = "com.huuhv.socialanalytics.controller.web")
public class WebExceptionHandler {

    /**
     * Session đã đăng nhập nhưng không map được sang user trong DB (vd tài khoản đã bị xoá): fail closed — huỷ session
     * rồi về trang đăng nhập. Phải đăng xuất trước khi redirect, nếu không {@code /login} lại chuyển về
     * {@code /dashboard} (vòng lặp).
     */
    @ExceptionHandler(AuthenticationRequiredException.class)
    public String handleAuthenticationRequired(HttpServletRequest request, HttpServletResponse response) {
        log.warn("Authenticated session does not map to a known user; session invalidated");
        new SecurityContextLogoutHandler().logout(request, response, SecurityContextHolder.getContext()
                .getAuthentication());
        return "redirect:/login?error";
    }
}

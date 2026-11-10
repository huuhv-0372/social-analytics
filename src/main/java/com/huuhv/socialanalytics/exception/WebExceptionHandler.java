package com.huuhv.socialanalytics.exception;

import com.huuhv.socialanalytics.util.StaleSessionLogout;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Xử lý exception nghiệp vụ của các trang Thymeleaf ({@code controller/web}). REST API dùng {@link ApiExceptionHandler}.
 */
@ControllerAdvice(basePackages = "com.huuhv.socialanalytics.controller.web")
public class WebExceptionHandler {

    /**
     * Session đã đăng nhập nhưng không map được sang user trong DB (vd tài khoản bị xoá giữa request, sau khi đã qua
     * {@code StaleSessionFilter}): fail closed — huỷ session (cùng logic với filter, {@link StaleSessionLogout}) rồi về
     * trang đăng nhập. Phải đăng xuất trước khi redirect, nếu không {@code /login} lại chuyển về {@code /dashboard}
     * (vòng lặp).
     */
    @ExceptionHandler(AuthenticationRequiredException.class)
    public String handleAuthenticationRequired(HttpServletRequest request, HttpServletResponse response) {
        StaleSessionLogout.logout(request, response, SecurityContextHolder.getContext().getAuthentication());
        return "redirect:/login?error";
    }
}

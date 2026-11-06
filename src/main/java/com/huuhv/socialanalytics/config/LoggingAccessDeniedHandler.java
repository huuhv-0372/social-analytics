package com.huuhv.socialanalytics.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.csrf.CsrfException;

import java.io.IOException;
import java.util.Objects;

/**
 * Ghi log sự kiện bảo mật khi request bị từ chối 403 (OWASP A09) rồi giao lại cho handler mặc định.
 * Chỉ log method, đường dẫn (đã bỏ CR/LF) và user — không log token, cookie hay header.
 */
@Slf4j
class LoggingAccessDeniedHandler implements AccessDeniedHandler {

    private final AccessDeniedHandler delegate = new AccessDeniedHandlerImpl();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException, ServletException {
        String reason = ex instanceof CsrfException ? "missing or invalid CSRF token" : "access denied";
        log.warn("Forbidden ({}): {} {} user={}", reason, request.getMethod(), sanitize(request.getRequestURI()),
                sanitize(Objects.requireNonNullElse(request.getRemoteUser(), "anonymous")));
        delegate.handle(request, response, ex);
    }

    private static String sanitize(String value) {
        return value.replaceAll("[\\r\\n]", "_");
    }
}

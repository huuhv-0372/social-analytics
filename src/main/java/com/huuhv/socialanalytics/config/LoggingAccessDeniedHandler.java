package com.huuhv.socialanalytics.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.huuhv.socialanalytics.util.LogSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.access.RequestMatcherDelegatingAccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * Ghi log sự kiện bảo mật khi request bị từ chối 403 (OWASP A09) rồi trả response theo loại request:
 * REST → {@code ProblemDetail} ({@link ApiAccessDeniedHandler}), trang web → handler mặc định (trang lỗi 403).
 * Chỉ log method, đường dẫn (đã bỏ CR/LF) và user — không log token, cookie hay header.
 */
@Slf4j
class LoggingAccessDeniedHandler implements AccessDeniedHandler {

    private final AccessDeniedHandler delegate;

    LoggingAccessDeniedHandler(RequestMatcher restRequestMatcher) {
        LinkedHashMap<RequestMatcher, AccessDeniedHandler> handlers = new LinkedHashMap<>();
        handlers.put(restRequestMatcher, new ApiAccessDeniedHandler());
        this.delegate = new RequestMatcherDelegatingAccessDeniedHandler(handlers, new AccessDeniedHandlerImpl());
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException, ServletException {
        String reason = ex instanceof CsrfException ? "missing or invalid CSRF token" : "access denied";
        log.warn("Forbidden ({}): {} {} user={}", reason, request.getMethod(),
                LogSanitizer.stripLineBreaks(request.getRequestURI()),
                LogSanitizer.stripLineBreaks(Objects.requireNonNullElse(request.getRemoteUser(), "anonymous")));
        delegate.handle(request, response, ex);
    }
}

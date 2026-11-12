package com.huuhv.socialanalytics.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * Request REST bị từ chối 403 (thiếu/sai CSRF, không đủ quyền) nhận {@code ProblemDetail} giống
 * {@link ApiAuthenticationEntryPoint} và {@code ApiExceptionHandler}, thay vì JSON mặc định của {@code /error}.
 * Không phân biệt lý do trong body (lý do chỉ ghi log ở {@link LoggingAccessDeniedHandler}).
 */
class ApiAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        ProblemDetailResponses.write(response, HttpStatus.FORBIDDEN, "Access denied");
    }
}

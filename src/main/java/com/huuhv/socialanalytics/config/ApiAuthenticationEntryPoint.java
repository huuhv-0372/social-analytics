package com.huuhv.socialanalytics.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Request REST chưa đăng nhập (hoặc sai thông tin đăng nhập) nhận 401 dạng {@code ProblemDetail} (RFC 9457) giống
 * {@code ApiExceptionHandler}, thay vì redirect về trang login. Body cố định — không echo đường dẫn hay lý do lỗi
 * xác thực ra client (OWASP A10).
 */
class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String BODY = """
            {"type":"about:blank","title":"Unauthorized","status":401,"detail":"Authentication required"}""";

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        // TẠM THỜI tới Task 3.4: báo client (Swagger UI, curl) dùng HTTP Basic
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"social-analytics\"");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(BODY);
    }
}

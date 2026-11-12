package com.huuhv.socialanalytics.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * Request REST chưa đăng nhập nhận 401 dạng {@code ProblemDetail} (RFC 9457) giống
 * {@code ApiExceptionHandler}, thay vì redirect về trang login. Body cố định — không echo đường dẫn hay lý do lỗi
 * xác thực ra client (OWASP A10). Không gửi {@code WWW-Authenticate}: app chỉ đăng nhập bằng OAuth2 trên trình duyệt,
 * challenge Basic sẽ khiến trình duyệt hiện hộp thoại đăng nhập không dùng được.
 */
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        ProblemDetailResponses.write(response, HttpStatus.UNAUTHORIZED, "Authentication required");
    }
}

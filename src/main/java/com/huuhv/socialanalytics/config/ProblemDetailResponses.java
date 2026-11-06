package com.huuhv.socialanalytics.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Ghi lỗi ở tầng filter Security (chưa vào tới {@code ApiExceptionHandler}) dưới dạng {@code ProblemDetail}
 * (RFC 9457) cho REST. Body là hằng số — không echo đường dẫn, lý do lỗi hay dữ liệu request (OWASP A10).
 */
final class ProblemDetailResponses {

    private ProblemDetailResponses() {
    }

    static void write(HttpServletResponse response, HttpStatus status, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // title/detail là hằng số trong code (không chứa ký tự cần escape JSON)
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + status.getReasonPhrase()
                + "\",\"status\":" + status.value() + ",\"detail\":\"" + detail + "\"}");
    }
}

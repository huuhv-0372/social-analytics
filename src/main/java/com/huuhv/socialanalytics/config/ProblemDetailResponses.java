package com.huuhv.socialanalytics.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/**
 * Ghi lỗi ở tầng filter Security (chưa vào tới {@code ApiExceptionHandler}) dưới dạng {@code ProblemDetail}
 * (RFC 9457) cho REST. Body là hằng số — không echo đường dẫn, lý do lỗi hay dữ liệu request (OWASP A10).
 * <p>
 * Serialize {@link ProblemDetail} bằng converter Jackson của Spring (đã gắn {@code ProblemDetailJacksonMixin}) giống
 * cách Spring MVC ghi body của {@code ApiExceptionHandler}: JSON luôn được escape đúng và cùng cấu trúc ở cả 2 tầng.
 */
final class ProblemDetailResponses {

    // Thread-safe sau khi khởi tạo; constructor nhận Builder tự thêm ProblemDetailJacksonMixin
    private static final JacksonJsonHttpMessageConverter CONVERTER =
            new JacksonJsonHttpMessageConverter(JsonMapper.builder());

    private ProblemDetailResponses() {
    }

    static void write(HttpServletResponse response, HttpStatus status, String detail) throws IOException {
        ServletServerHttpResponse output = new ServletServerHttpResponse(response);
        output.setStatusCode(status);
        CONVERTER.write(ProblemDetail.forStatusAndDetail(status, detail), MediaType.APPLICATION_PROBLEM_JSON, output);
        output.flush();
    }
}

package com.huuhv.socialanalytics.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;

import java.util.function.Supplier;

/**
 * Nhận CSRF token ở cả 2 dạng mà client của app gửi lên:
 * <ul>
 *     <li><b>đã mask</b> (BREACH protection) — giá trị {@code ${_csrf.token}} render trong form hidden {@code _csrf} và
 *     {@code <meta name="_csrf">}, JS {@code fetch} gửi lại qua header;</li>
 *     <li><b>raw</b> — giá trị cookie {@code XSRF-TOKEN} mà Swagger UI / Postman gửi lại qua header {@code X-XSRF-TOKEN}.</li>
 * </ul>
 * Khác {@code csrf.spa()} mặc định (header luôn so khớp raw nên token từ meta bị từ chối). Không tự viết thuật toán:
 * thử giải mask bằng {@link XorCsrfTokenRequestAttributeHandler}, không giải được (độ dài không khớp) thì so khớp raw.
 * <p>
 * Fail closed (OWASP A10): token đúng độ dài nhưng bị sửa có thể giải mask ra byte không phải UTF-8 — Spring Security
 * ném {@link IllegalArgumentException} thay vì trả {@code null}. Bắt lại và coi như "không phải token đã mask" → so
 * khớp raw thất bại → {@code CsrfFilter} trả 403 (được {@code LoggingAccessDeniedHandler} log), không thành lỗi 500.
 */
final class CsrfHeaderOrFormTokenRequestHandler implements CsrfTokenRequestHandler {

    private final XorCsrfTokenRequestAttributeHandler xor = new XorCsrfTokenRequestAttributeHandler();
    private final CsrfTokenRequestAttributeHandler plain = new CsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
        // Token đưa vào request attribute (template) luôn được mask, mỗi response một giá trị khác nhau
        xor.handle(request, response, csrfToken);
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        String unmasked = unmask(request, csrfToken);
        return unmasked != null ? unmasked : plain.resolveCsrfTokenValue(request, csrfToken);
    }

    private String unmask(HttpServletRequest request, CsrfToken csrfToken) {
        try {
            return xor.resolveCsrfTokenValue(request, csrfToken);
        } catch (IllegalArgumentException e) {
            // Utf8.decode thất bại với token bị sửa — không log giá trị token (CLAUDE.md 5.5)
            return null;
        }
    }
}

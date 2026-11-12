package com.huuhv.socialanalytics.security;

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
 * thử giải mask bằng {@link XorCsrfTokenRequestAttributeHandler}, không giải được (độ dài không khớp, không phải UTF-8
 * hợp lệ) thì so khớp raw.
 */
public final class CsrfHeaderOrFormTokenRequestHandler implements CsrfTokenRequestHandler {

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

    /**
     * Giải mask token do client gửi; {@code null} nếu giá trị không phải token đã mask hợp lệ.
     * {@link XorCsrfTokenRequestAttributeHandler} ném {@link IllegalArgumentException} khi kết quả XOR không phải UTF-8
     * hợp lệ (token bị sửa / giả mạo) — coi như không giải được để request đi tiếp nhánh so khớp raw và bị
     * {@code CsrfFilter} từ chối 403, thay vì để exception thoát ra thành 500 (OWASP A10, fail closed).
     */
    private String unmask(HttpServletRequest request, CsrfToken csrfToken) {
        try {
            return xor.resolveCsrfTokenValue(request, csrfToken);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

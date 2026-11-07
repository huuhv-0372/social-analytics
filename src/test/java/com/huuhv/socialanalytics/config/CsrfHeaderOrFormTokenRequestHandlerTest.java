package com.huuhv.socialanalytics.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Logic nhận CSRF token của {@link CsrfHeaderOrFormTokenRequestHandler}. Test MockMvc dùng post-processor
 * {@code csrf()} thay {@code CsrfTokenRepository} nên không đi qua handler này — kiểm tra trực tiếp ở đây; luồng
 * tích hợp (cookie thật) nằm ở {@code SecurityConfigWebTest}. {@code CsrfFilter} chấp nhận request khi giá trị trả về
 * bằng token thật, nên "bị từ chối" = trả về giá trị khác token thật.
 */
class CsrfHeaderOrFormTokenRequestHandlerTest {

    private static final String HEADER = "X-XSRF-TOKEN";
    private static final String PARAMETER = "_csrf";

    private final CsrfHeaderOrFormTokenRequestHandler handler = new CsrfHeaderOrFormTokenRequestHandler();
    private final CsrfToken token = new DefaultCsrfToken(HEADER, PARAMETER, UUID.randomUUID().toString());

    @Test
    void maskedTokenFromMetaTagInHeaderResolvesToRawToken() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/posts");
        request.addHeader(HEADER, maskedToken());

        assertThat(handler.resolveCsrfTokenValue(request, token)).isEqualTo(token.getToken());
    }

    @Test
    void maskedTokenInFormParameterResolvesToRawToken() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/logout");
        request.setParameter(PARAMETER, maskedToken());

        assertThat(handler.resolveCsrfTokenValue(request, token)).isEqualTo(token.getToken());
    }

    @Test
    void rawCookieValueInHeaderIsAccepted() {
        // Swagger UI / Postman gửi lại nguyên giá trị cookie XSRF-TOKEN
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/posts");
        request.addHeader(HEADER, token.getToken());

        assertThat(handler.resolveCsrfTokenValue(request, token)).isEqualTo(token.getToken());
    }

    @Test
    void tamperedMaskedTokenDoesNotResolveToRawToken() {
        // Sửa 1 byte ở nửa "xored": giải mask ra ký tự ASCII khác → vẫn là UTF-8 hợp lệ nhưng sai token
        byte[] bytes = Base64.getUrlDecoder().decode(maskedToken());
        bytes[bytes.length - 1] ^= 0x01;
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/posts");
        request.addHeader(HEADER, Base64.getUrlEncoder().encodeToString(bytes));

        assertThat(handler.resolveCsrfTokenValue(request, token)).isNotEqualTo(token.getToken());
    }

    @Test
    void maskedTokenDecodingToInvalidUtf8IsRejectedInsteadOfThrowing() {
        // Đúng độ dài token đã mask nhưng giải mask ra byte 0xFF (không phải UTF-8) — trước đây ném
        // IllegalArgumentException ra khỏi CsrfFilter (500) thay vì từ chối (403)
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/posts");
        request.addHeader(HEADER, invalidUtf8MaskedToken(token.getToken()));

        assertThat(handler.resolveCsrfTokenValue(request, token)).isNotEqualTo(token.getToken());
    }

    @Test
    void wrongRawTokenDoesNotResolveToRawToken() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/posts");
        request.addHeader(HEADER, UUID.randomUUID().toString());

        assertThat(handler.resolveCsrfTokenValue(request, token)).isNotEqualTo(token.getToken());
    }

    @Test
    void maskedTokenOfAnotherSessionIsRejected() {
        CsrfToken otherSession = new DefaultCsrfToken(HEADER, PARAMETER, UUID.randomUUID().toString());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/posts");
        request.addHeader(HEADER, maskedToken(otherSession));

        assertThat(handler.resolveCsrfTokenValue(request, token)).isNotEqualTo(token.getToken());
    }

    @Test
    void missingTokenResolvesToNull() {
        assertThat(handler.resolveCsrfTokenValue(new MockHttpServletRequest("POST", "/api/posts"), token)).isNull();
    }

    @Test
    void tokenExposedToTemplatesIsMaskedDifferentlyOnEachResponse() {
        // BREACH: giá trị render vào <meta>/form khác giá trị raw và khác nhau giữa các response
        String first = maskedToken();
        String second = maskedToken();

        assertThat(first).isNotEqualTo(token.getToken());
        assertThat(first).isNotEqualTo(second);
    }

    private String maskedToken() {
        return maskedToken(token);
    }

    /**
     * Token dạng đã mask ({@code Base64Url(random ‖ xored)}, mỗi nửa dài bằng token raw) với random = 0x00 và
     * xored = 0xFF → giải mask ra toàn byte 0xFF, không phải UTF-8 hợp lệ.
     */
    static String invalidUtf8MaskedToken(String rawToken) {
        int size = rawToken.getBytes(StandardCharsets.UTF_8).length;
        byte[] bytes = new byte[size * 2];
        Arrays.fill(bytes, size, bytes.length, (byte) 0xFF);
        return Base64.getUrlEncoder().encodeToString(bytes);
    }

    /** Giá trị mà template nhận được (request attribute do {@code handle} đặt). */
    private String maskedToken(CsrfToken source) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        handler.handle(request, new MockHttpServletResponse(), () -> source);
        return ((CsrfToken) request.getAttribute(CsrfToken.class.getName())).getToken();
    }
}

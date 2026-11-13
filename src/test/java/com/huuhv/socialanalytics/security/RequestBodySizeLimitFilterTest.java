package com.huuhv.socialanalytics.security;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestBodySizeLimitFilterTest {

    private static final int MAX_BYTES = 16;

    private final RequestBodySizeLimitFilter filter = new RequestBodySizeLimitFilter(MAX_BYTES);

    private static MockHttpServletRequest post(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/services");
        request.setServletPath("/services");
        request.setContent(body);
        return request;
    }

    /** Như request chunked: không khai báo Content-Length (MockHttpServletRequest luôn suy ra từ nội dung). */
    private static HttpServletRequest withoutContentLength(HttpServletRequest request) {
        return new HttpServletRequestWrapper(request) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
    }

    @Test
    void declaredBodyOverLimitIsRejectedWith413WithoutReachingServlet() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(post(new byte[MAX_BYTES + 1]), response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void bodyWithinLimitIsReadUnchanged() throws Exception {
        byte[] body = "<a>0123456789</a".getBytes(StandardCharsets.UTF_8);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(withoutContentLength(post(body)), new MockHttpServletResponse(), chain);

        try (InputStream in = chain.getRequest().getInputStream()) {
            assertThat(in.readAllBytes()).isEqualTo(body);
        }
    }

    /**
     * Không khai báo Content-Length (chunked): stream ném lỗi ngay khi đọc quá giới hạn → phía đọc (SAAJ) dừng, không
     * giữ quá giới hạn trong bộ nhớ.
     */
    @Test
    void undeclaredBodyFailsWhileReadingPastLimit() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(withoutContentLength(post(new byte[MAX_BYTES * 4])), new MockHttpServletResponse(), chain);

        HttpServletRequest limited = (HttpServletRequest) chain.getRequest();
        assertThat(RequestBodySizeLimitFilter.isBodyTooLarge(limited)).isFalse();
        assertThatThrownBy(() -> limited.getInputStream().readAllBytes())
                .isInstanceOf(RequestBodySizeLimitFilter.BodyTooLargeException.class)
                .hasMessage("Request body exceeds " + MAX_BYTES + " bytes");
        // Phía đọc (SAAJ) có thể nuốt cause → handler nhận ra qua dấu trên request
        assertThat(RequestBodySizeLimitFilter.isBodyTooLarge(limited)).isTrue();
    }

    @Test
    void readerIsLimitedToo() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(withoutContentLength(post(new byte[MAX_BYTES * 4])), new MockHttpServletResponse(), chain);

        ServletRequest limited = chain.getRequest();
        assertThatThrownBy(() -> limited.getReader().read(new char[MAX_BYTES * 4]))
                .isInstanceOf(RequestBodySizeLimitFilter.BodyTooLargeException.class);
    }

    @Test
    void rejectsNonPositiveLimit() {
        assertThatThrownBy(() -> new RequestBodySizeLimitFilter(0)).isInstanceOf(IllegalArgumentException.class);
    }
}

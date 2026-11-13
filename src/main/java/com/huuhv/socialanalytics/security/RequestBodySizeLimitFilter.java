package com.huuhv.socialanalytics.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Giới hạn dung lượng body của request (OWASP A06) cho endpoint đọc cả body vào bộ nhớ trước khi validate — vd SAAJ
 * dựng toàn bộ message SOAP thành DOM rồi mới kiểm tra XSD. {@code server.tomcat.max-http-form-post-size} chỉ áp dụng
 * cho form, không chặn được body XML.
 * <ul>
 *     <li>{@code Content-Length} vượt giới hạn → 413 ngay, không đọc body, không chuyển tiếp.</li>
 *     <li>Không có {@code Content-Length} (chunked) hoặc khai thiếu → đọc qua stream đếm byte: vượt giới hạn thì
 *     stream ném {@link BodyTooLargeException} — phía đọc (SAAJ) dừng, request bị từ chối như message hỏng
 *     (Spring-WS trả 400), không bao giờ giữ quá giới hạn trong bộ nhớ.</li>
 * </ul>
 * Thông điệp lỗi / log không chứa nội dung body.
 */
@Slf4j
public class RequestBodySizeLimitFilter extends OncePerRequestFilter {

    /** Request attribute đánh dấu body vượt giới hạn trong lúc đọc — xem {@link #isBodyTooLarge}. */
    private static final String BODY_TOO_LARGE_ATTRIBUTE = RequestBodySizeLimitFilter.class.getName() + ".TOO_LARGE";

    private final long maxBytes;

    /**
     * Body của request đã vượt giới hạn trong lúc đọc. Phía đọc có thể bọc {@link BodyTooLargeException} thành lỗi
     * khác mà không giữ cause (vd SAAJ → "Unable to internalize message") — dấu này giúp trả đúng 413 thay vì 400.
     */
    public static boolean isBodyTooLarge(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(BODY_TOO_LARGE_ATTRIBUTE));
    }

    public RequestBodySizeLimitFilter(long maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.maxBytes = maxBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > maxBytes) {
            // Audit (OWASP A09): kích thước khai báo + đường dẫn servlet (cố định, không chứa input tuỳ ý của client)
            log.warn("Rejected request to {}: body of {} bytes exceeds {} bytes", request.getServletPath(), declared,
                    maxBytes);
            response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
            return;
        }
        chain.doFilter(new LimitedRequest(request, maxBytes), response);
    }

    /** Body vượt giới hạn khi đọc (request không khai báo / khai thiếu {@code Content-Length}). */
    public static final class BodyTooLargeException extends IOException {

        BodyTooLargeException(long maxBytes) {
            super("Request body exceeds " + maxBytes + " bytes");
        }
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private final long maxBytes;
        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new LimitedInputStream(super.getInputStream(), maxBytes,
                        () -> setAttribute(BODY_TOO_LARGE_ATTRIBUTE, Boolean.TRUE));
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.ISO_8859_1 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class LimitedInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maxBytes;
        private final Runnable onExceeded;
        private long count;

        LimitedInputStream(ServletInputStream delegate, long maxBytes, Runnable onExceeded) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
            this.onExceeded = onExceeded;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                count(read);
            }
            return read;
        }

        private void count(int read) throws BodyTooLargeException {
            count += read;
            if (count > maxBytes) {
                onExceeded.run();
                log.warn("Rejected request body: more than {} bytes", maxBytes);
                throw new BodyTooLargeException(maxBytes);
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}

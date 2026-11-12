package com.huuhv.socialanalytics.util;

import java.util.regex.Pattern;

/**
 * Bỏ CR/LF khỏi dữ liệu do người dùng/provider kiểm soát trước khi ghi log (chống log injection — OWASP A05).
 * Là lớp chặn chính ngay tại code; {@code %replace} trong {@code LOG_PATTERN} của {@code logback-spring.xml} chỉ là lớp
 * thứ hai (không áp dụng nếu sau này đổi appender, vd log JSON). Pattern biên dịch 1 lần, dùng chung (thread-safe).
 */
public final class LogSanitizer {

    private static final Pattern LINE_BREAKS = Pattern.compile("[\\r\\n]");

    private LogSanitizer() {
    }

    /** Thay CR/LF bằng {@code _} để dấu hiệu chèn dòng vẫn nhìn thấy trong log; {@code null} → {@code "null"}. */
    public static String stripLineBreaks(String value) {
        return value == null ? "null" : LINE_BREAKS.matcher(value).replaceAll("_");
    }
}

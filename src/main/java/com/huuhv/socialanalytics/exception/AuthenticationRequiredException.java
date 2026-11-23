package com.huuhv.socialanalytics.exception;

/**
 * Không xác định được user đang đăng nhập — fail closed (OWASP A10).
 */
public class AuthenticationRequiredException extends RuntimeException {

    public AuthenticationRequiredException() {
        super("Authentication required");
    }
}

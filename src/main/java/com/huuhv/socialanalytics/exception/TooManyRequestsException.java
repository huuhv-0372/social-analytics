package com.huuhv.socialanalytics.exception;

import lombok.Getter;

import java.time.Duration;

/**
 * User gọi thao tác tốn tài nguyên (vd export) quá tần suất cho phép → 429 kèm header {@code Retry-After}.
 */
@Getter
public class TooManyRequestsException extends RuntimeException {

    private final Duration retryAfter;

    public TooManyRequestsException(Duration retryAfter) {
        super("Too many requests, please retry later");
        this.retryAfter = retryAfter;
    }
}

package com.huuhv.socialanalytics.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;

import java.lang.reflect.Method;

/**
 * Exception thoát khỏi method {@code @Async} trả {@code void} không có ai nhận (không có Future) → log lại thay vì mất
 * dấu. Method trả {@code CompletableFuture} thì exception nằm trong future, caller tự xử lý.
 * <p>
 * Chỉ log tên method, <b>không</b> log tham số: tham số có thể chứa access token / dữ liệu người dùng (CLAUDE.md 5.5).
 */
@Slf4j
public class LoggingAsyncUncaughtExceptionHandler implements AsyncUncaughtExceptionHandler {

    @Override
    public void handleUncaughtException(Throwable ex, Method method, Object... params) {
        log.error("Async method {}.{} failed", method.getDeclaringClass().getSimpleName(), method.getName(), ex);
    }
}

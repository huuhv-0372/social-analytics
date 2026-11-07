package com.huuhv.socialanalytics.config;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * Truyền MDC ({@code jobId}, {@code importId}...) của thread gửi task sang thread của pool để log đa luồng trace được
 * theo job. Thread trong pool được tái sử dụng → sau mỗi task khôi phục MDC cũ của thread đó (thường là rỗng), không
 * để {@code jobId} của job trước rò sang task sau.
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // Chụp tại thread gửi task (vd thread scheduler đang chạy job)
        Map<String, String> submitterContext = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            setContext(submitterContext);
            try {
                runnable.run();
            } finally {
                // CallerRunsPolicy chạy task ngay trên thread gửi → khôi phục để không xoá MDC của thread đó
                setContext(previous);
            }
        };
    }

    private static void setContext(Map<String, String> context) {
        if (context == null || context.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }
}

package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.enums.DashboardEventType;

import java.time.Instant;

/**
 * Sự kiện trong JVM (Spring {@code ApplicationEvent}): dữ liệu hiển thị trên dashboard vừa đổi.
 * {@code messaging/DashboardEventProducer} nhận sự kiện <b>sau khi transaction commit</b> (hoặc ngay lập tức nếu không
 * có transaction) rồi phát lên JMS topic để mọi instance đẩy xuống client của mình.
 * <p>
 * Cố ý không mang userId hay số liệu: event được broadcast tới mọi client, mỗi client tự lấy dữ liệu của mình qua API.
 */
public record DashboardChangedEvent(DashboardEventType type, Instant occurredAt) {
}

package com.huuhv.socialanalytics.websocket;

import com.huuhv.socialanalytics.enums.DashboardEventType;

import java.time.Instant;

/**
 * Payload JSON gửi tới mọi client trên {@value StompDestinations#METRICS_TOPIC}, vd
 * {@code {"type":"METRICS_UPDATED","occurredAt":"2026-11-12T03:00:00Z"}}. Chỉ là tín hiệu "dữ liệu đã đổi": không chứa
 * userId hay số liệu (mọi user đều nhận) — client tự gọi {@code GET /api/chart-data}.
 */
public record DashboardEventPayload(DashboardEventType type, Instant occurredAt) {
}

package com.huuhv.socialanalytics.websocket;

import com.huuhv.socialanalytics.enums.DashboardEventType;

import java.time.Instant;

/**
 * Payload JSON gửi tới user trên {@value StompDestinations#USER_METRICS_DESTINATION}, vd
 * {@code {"type":"METRICS_UPDATED","occurredAt":"2026-11-12T03:00:00Z"}}. Chỉ là tín hiệu "dữ liệu của bạn đã đổi":
 * không chứa số liệu — client tự gọi {@code GET /api/chart-data}.
 */
public record DashboardEventPayload(DashboardEventType type, Instant occurredAt) {
}

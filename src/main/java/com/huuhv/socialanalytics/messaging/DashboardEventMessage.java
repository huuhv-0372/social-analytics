package com.huuhv.socialanalytics.messaging;

import com.huuhv.socialanalytics.enums.DashboardEventType;
import com.huuhv.socialanalytics.service.DashboardChangedEvent;

import java.time.Instant;

/**
 * Payload JSON trên topic {@value TopicNames#DASHBOARD_EVENTS} (alias {@value MessageTypes#DASHBOARD_EVENT}).
 * Chỉ thêm field, không đổi/xoá (message đang trên broker hoặc instance chạy bản cũ khi deploy cuốn chiếu).
 */
public record DashboardEventMessage(DashboardEventType type, Instant occurredAt) {

    static DashboardEventMessage from(DashboardChangedEvent event) {
        return new DashboardEventMessage(event.type(), event.occurredAt());
    }
}

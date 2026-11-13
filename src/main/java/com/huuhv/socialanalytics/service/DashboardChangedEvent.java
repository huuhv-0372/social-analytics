package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.enums.DashboardEventType;

import java.time.Instant;
import java.util.Set;

/**
 * Sự kiện trong JVM (Spring {@code ApplicationEvent}): dữ liệu dashboard của các user {@code userIds} vừa đổi.
 * {@code messaging/DashboardEventProducer} nhận sự kiện <b>sau khi transaction commit</b> (hoặc ngay lập tức nếu không
 * có transaction) rồi phát lên JMS topic để instance nào đang giữ kết nối WebSocket của các user đó đẩy xuống.
 * <p>
 * Chỉ mang id user nội bộ (để định tuyến), không mang số liệu: client tự lấy dữ liệu của mình qua API.
 *
 * @param userIds không rỗng
 */
public record DashboardChangedEvent(DashboardEventType type, Instant occurredAt, Set<Long> userIds) {

    public DashboardChangedEvent {
        userIds = Set.copyOf(userIds);
    }
}

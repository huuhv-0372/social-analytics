package com.huuhv.socialanalytics.messaging;

import com.huuhv.socialanalytics.enums.DashboardEventType;

import java.time.Instant;
import java.util.List;

/**
 * Payload JSON trên topic {@value TopicNames#DASHBOARD_EVENTS} (alias {@value MessageTypes#DASHBOARD_EVENT}).
 * Chỉ thêm field, không đổi/xoá (message đang trên broker hoặc instance chạy bản cũ khi deploy cuốn chiếu).
 * <p>
 * Khác payload STOMP gửi xuống trình duyệt ({@code websocket/DashboardEventPayload}): message nội bộ giữa các instance
 * mang {@code userIds} để định tuyến, payload gửi user thì không.
 *
 * @param userIds id user nội bộ cần làm mới dashboard — tối đa {@link #MAX_USER_IDS} phần tử mỗi message
 */
public record DashboardEventMessage(DashboardEventType type, Instant occurredAt, List<Long> userIds) {

    /** Producer chia lô theo giới hạn này; listener bỏ message vượt giới hạn (dữ liệu từ broker không tin cậy). */
    public static final int MAX_USER_IDS = 500;
}

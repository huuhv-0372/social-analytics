package com.huuhv.socialanalytics.messaging;

import java.util.Map;

/**
 * Bảng alias ↔ class của message JMS: alias được ghi vào property {@code _type} của message thay cho tên class, và
 * chỉ class có trong bảng này mới được deserialize khi nhận (OWASP A08 — không nạp class theo tên do message gửi tới).
 * Thêm loại message mới thì khai báo alias ở đây; alias là một phần của "hợp đồng" message, đổi alias làm message
 * đang nằm trong queue không đọc được.
 */
public final class MessageTypes {

    public static final String IMPORT_COMPLETED = "importCompleted";

    public static final String DASHBOARD_EVENT = "dashboardEvent";

    public static final Map<String, Class<?>> TYPE_ID_MAPPINGS = Map.of(
            IMPORT_COMPLETED, ImportCompletedMessage.class,
            DASHBOARD_EVENT, DashboardEventMessage.class);

    private MessageTypes() {
    }
}

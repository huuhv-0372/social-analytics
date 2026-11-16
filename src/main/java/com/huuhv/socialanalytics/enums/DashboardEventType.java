package com.huuhv.socialanalytics.enums;

/**
 * Loại event "dữ liệu dashboard đã đổi" gửi tới client qua WebSocket — client nhận rồi tự gọi lại
 * {@code GET /api/chart-data} (dữ liệu lọc theo user đang đăng nhập). Tên enum là một phần của payload JSON (JMS và
 * STOMP): đổi/xoá giá trị làm client cũ và message đang trên broker không đọc được — chỉ thêm giá trị mới.
 */
public enum DashboardEventType {

    /** Job crawl metrics vừa cập nhật thành công ít nhất 1 tài khoản (snapshot + "Last updated time"). */
    METRICS_UPDATED,

    /** Listener {@code IMPORT_COMPLETED} vừa cộng 1 lần import vào thống kê tổng hợp. */
    IMPORT_PROCESSED
}

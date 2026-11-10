package com.huuhv.socialanalytics.messaging;

/**
 * Tên queue JMS dùng chung cho producer và {@code @JmsListener} — không hardcode chuỗi ở nơi khác.
 * Broker tự tạo queue (anycast) khi có producer/consumer đầu tiên.
 */
public final class QueueNames {

    /** Import Excel đã commit → listener cập nhật thống kê tổng hợp. */
    public static final String IMPORT_COMPLETED = "import-completed";

    private QueueNames() {
    }
}

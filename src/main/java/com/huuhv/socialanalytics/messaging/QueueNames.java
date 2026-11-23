package com.huuhv.socialanalytics.messaging;

/**
 * Tên queue JMS dùng chung cho producer, {@code @JmsListener} và cấu hình broker — không hardcode chuỗi ở nơi khác.
 * Retry/DLQ trên broker khai báo lại các tên này trong {@code artemis/broker.properties} (resources) — đổi tên ở đây thì
 * sửa cả file đó; {@code ArtemisEmbeddedConfigTest} nạp file và đối chiếu với các hằng số này.
 */
public final class QueueNames {

    /** Import Excel đã commit → listener cập nhật thống kê tổng hợp. */
    public static final String IMPORT_COMPLETED = "import-completed";

    /**
     * Dead letter queue của {@link #IMPORT_COMPLETED}: message giao đủ số lần tối đa vẫn lỗi được broker chuyển sang
     * đây (address + queue anycast cùng tên, tạo sẵn khi broker khởi động).
     */
    public static final String IMPORT_COMPLETED_DLQ = "DLQ." + IMPORT_COMPLETED;

    private QueueNames() {
    }
}

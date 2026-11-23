package com.huuhv.socialanalytics.enums;

/**
 * Trạng thái xử lý message {@code IMPORT_COMPLETED} của 1 lần import ({@code import_histories.status}).
 * <ul>
 *     <li>{@code PENDING → PROCESSED}: listener cộng vào thống kê.</li>
 *     <li>{@code PENDING → DEAD_LETTERED}: giao lỗi đủ số lần tối đa, message vào DLQ.</li>
 *     <li>{@code DEAD_LETTERED → PROCESSED}: message được gửi lại vào queue chính (sau khi khắc phục lỗi) và xử lý
 *     thành công.</li>
 * </ul>
 */
public enum ImportStatus {
    /** Đã import (bài viết đã commit), listener chưa cập nhật thống kê tổng hợp. */
    PENDING,
    /** Listener đã cộng lần import vào thống kê ({@code processed_at}) — nhận lại message thì bỏ qua. */
    PROCESSED,
    /**
     * Message lỗi đủ số lần giao tối đa và đã vào DLQ — thống kê <b>chưa</b> có lần import này. Gửi lại message
     * {@code IMPORT_COMPLETED} (dựng từ {@code import_histories}) sau khi khắc phục lỗi thì vẫn được cộng (đúng 1 lần).
     */
    DEAD_LETTERED
}

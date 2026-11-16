package com.huuhv.socialanalytics.dto.response;

import java.util.List;

/**
 * Kết quả import: dòng hợp lệ được lưu, dòng lỗi trả về kèm số dòng (không fail cả file).
 *
 * @param importId  id của lần import (lịch sử import) — thống kê tổng hợp được cập nhật bất đồng bộ sau khi trả về;
 *                  {@code null} khi mọi dòng đều lỗi: không có gì được ghi (không lịch sử, không thống kê)
 * @param totalRows số dòng dữ liệu trong file (không tính header và dòng trống)
 * @param created   số bài viết tạo mới
 * @param updated   số bài viết đã có (cùng provider + externalId) được cập nhật
 * @param failed    số dòng bị bỏ qua do lỗi
 */
public record ImportResultResponse(Long importId, int totalRows, int created, int updated, int failed,
                                   List<RowError> errors) {

    /**
     * @param row    số dòng theo cách Excel hiển thị (dòng 1 là header)
     * @param column tên cột trong file
     */
    public record RowError(int row, String column, String message) {
    }
}

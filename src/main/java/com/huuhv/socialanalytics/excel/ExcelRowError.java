package com.huuhv.socialanalytics.excel;

/**
 * Lỗi của 1 ô / 1 dòng. Thông điệp không chứa giá trị ô (tránh echo dữ liệu người dùng ra response).
 *
 * @param rowNumber số dòng theo cách Excel hiển thị (bắt đầu từ 1)
 * @param column    tên cột ({@link ExcelColumn#name()}), có thể null với lỗi cả dòng
 */
public record ExcelRowError(int rowNumber, String column, String message) {
}

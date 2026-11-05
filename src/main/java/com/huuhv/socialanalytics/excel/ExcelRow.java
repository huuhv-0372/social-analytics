package com.huuhv.socialanalytics.excel;

import java.util.List;

/**
 * Một dòng dữ liệu đã đọc. Ô lỗi chuyển kiểu để field tương ứng là null và có lỗi trong {@code errors}.
 *
 * @param rowNumber số dòng theo cách Excel hiển thị (bắt đầu từ 1, dòng 1 là header)
 * @param errors    lỗi chuyển kiểu của dòng (rỗng khi mọi ô hợp lệ)
 */
public record ExcelRow<T>(int rowNumber, T value, List<ExcelRowError> errors) {

    public boolean isValid() {
        return errors.isEmpty();
    }
}

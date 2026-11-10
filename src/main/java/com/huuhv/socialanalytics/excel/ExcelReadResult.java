package com.huuhv.socialanalytics.excel;

import java.util.List;

/**
 * Kết quả đọc file: mọi dòng dữ liệu (kể cả dòng có lỗi chuyển kiểu) theo thứ tự trong file.
 *
 * @param totalRows số dòng dữ liệu (không tính header và dòng trống)
 */
public record ExcelReadResult<T>(List<ExcelRow<T>> rows, int totalRows) {

    /** Dòng không có lỗi chuyển kiểu. */
    public List<ExcelRow<T>> validRows() {
        return rows.stream().filter(ExcelRow::isValid).toList();
    }

    /** Lỗi chuyển kiểu của mọi dòng, theo thứ tự dòng rồi thứ tự cột. */
    public List<ExcelRowError> errors() {
        return rows.stream().flatMap(row -> row.errors().stream()).toList();
    }
}

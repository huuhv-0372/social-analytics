package com.huuhv.socialanalytics.excel;

/**
 * Lỗi ở mức cả file (không mở được, thiếu cột, quá số dòng). Thông điệp an toàn để trả cho client.
 */
public class ExcelFileException extends RuntimeException {

    public ExcelFileException(String message) {
        super(message);
    }

    public ExcelFileException(String message, Throwable cause) {
        super(message, cause);
    }
}

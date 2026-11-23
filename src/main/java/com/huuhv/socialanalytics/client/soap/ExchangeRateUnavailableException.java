package com.huuhv.socialanalytics.client.soap;

/**
 * Không lấy được tỷ giá từ dịch vụ SOAP: lỗi kết nối / timeout, SOAP Fault, response không đúng hợp đồng hoặc không
 * hợp lệ. Message chỉ mô tả chung + tên loại lỗi gốc; cố ý không giữ exception gốc vì thông điệp của nó có thể chứa
 * dữ liệu do server bên kia gửi (fault string, nội dung XML — kể cả ký tự xuống dòng gây log injection qua {@code %ex}).
 */
public class ExchangeRateUnavailableException extends RuntimeException {

    public ExchangeRateUnavailableException(String message) {
        super(message);
    }
}

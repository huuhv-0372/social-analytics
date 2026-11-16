package com.huuhv.socialanalytics.exception;

import org.springframework.ws.soap.server.endpoint.annotation.FaultCode;
import org.springframework.ws.soap.server.endpoint.annotation.SoapFault;

/**
 * Cặp tiền tệ không được dịch vụ tỷ giá mock hỗ trợ. Endpoint SOAP trả SOAP Fault {@code Client} với thông điệp cố
 * định (không echo mã tiền tệ người gọi gửi lên).
 */
@SoapFault(faultCode = FaultCode.CLIENT, faultStringOrReason = UnsupportedCurrencyPairException.MESSAGE)
public class UnsupportedCurrencyPairException extends RuntimeException {

    public static final String MESSAGE = "Unsupported currency pair";

    public UnsupportedCurrencyPairException() {
        super(MESSAGE);
    }
}

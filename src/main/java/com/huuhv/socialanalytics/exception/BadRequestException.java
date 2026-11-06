package com.huuhv.socialanalytics.exception;

/**
 * Input vi phạm quy tắc nghiệp vụ (ngoài Bean Validation). Message trả về client nên chỉ chứa thông tin an toàn.
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}

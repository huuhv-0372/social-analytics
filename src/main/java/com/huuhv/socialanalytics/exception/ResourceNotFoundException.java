package com.huuhv.socialanalytics.exception;

/**
 * Không tìm thấy tài nguyên, hoặc tài nguyên không thuộc user hiện tại (trả 404 thay vì 403 để không lộ sự tồn tại — chống IDOR).
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String resource) {
        super(resource + " not found");
    }
}

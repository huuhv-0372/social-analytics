package com.huuhv.socialanalytics.client.social;

/**
 * Lỗi khi lấy dữ liệu từ mạng xã hội. Message chỉ mô tả chung — không chứa token hay response thô của provider.
 */
public class SocialPlatformException extends RuntimeException {

    public SocialPlatformException(String message) {
        super(message);
    }

    public SocialPlatformException(String message, Throwable cause) {
        super(message, cause);
    }
}

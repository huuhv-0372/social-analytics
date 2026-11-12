package com.huuhv.socialanalytics.client.social;

/**
 * Access token bị provider từ chối (rỗng, hết hạn, bị thu hồi). Lỗi nghiệp vụ: user phải đăng nhập lại, không thử lại.
 */
public class InvalidAccessTokenException extends SocialPlatformException {

    public InvalidAccessTokenException(String message) {
        super(message);
    }
}

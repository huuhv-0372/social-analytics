package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.entity.User;

/**
 * Xác định user đang đăng nhập từ SecurityContext. Không bao giờ tin userId do client gửi lên (OWASP A01).
 */
public interface CurrentUserService {

    /**
     * @throws com.huuhv.socialanalytics.exception.AuthenticationRequiredException khi request chưa đăng nhập
     */
    User getCurrentUser();
}

package com.huuhv.socialanalytics.controller.web;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Tên hiển thị của user đang đăng nhập cho layout ({@code ${displayName}}), dùng chung mọi trang web.
 * Principal OAuth2 có tên là id phía provider (vd id Facebook) nên ưu tiên thuộc tính {@code name} từ user info.
 * Giá trị hiển thị bằng {@code th:text} (escape HTML) vì đến từ provider bên ngoài.
 */
@ControllerAdvice(basePackages = "com.huuhv.socialanalytics.controller.web")
public class CurrentUserModelAdvice {

    @ModelAttribute("displayName")
    public String displayName(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        if (authentication.getPrincipal() instanceof OAuth2User oauth2User
                && oauth2User.getAttribute("name") instanceof String name
                && !name.isBlank()) {
            return name;
        }
        return authentication.getName();
    }
}

package com.huuhv.socialanalytics.controller.web;

import com.huuhv.socialanalytics.security.AppOAuth2User;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Tên hiển thị của user đang đăng nhập cho layout ({@code ${displayName}}), dùng chung mọi trang web.
 * <p>
 * Principal của app ({@link AppOAuth2User}) mang sẵn tên đã chuẩn hoá lúc đăng nhập (giống {@code users.display_name}:
 * bỏ ký tự điều khiển, cắt độ dài, dự phòng {@code username} / tên mặc định theo provider) → không lặp lại quy tắc ở
 * đây. Principal OAuth2 khác (session cũ, test) dùng thuộc tính {@code name}, không có thì tên principal.
 * Giá trị hiển thị bằng {@code th:text} (escape HTML) vì đến từ provider bên ngoài.
 */
@ControllerAdvice(basePackages = "com.huuhv.socialanalytics.controller.web")
public class CurrentUserModelAdvice {

    @ModelAttribute("displayName")
    public String displayName(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        if (authentication.getPrincipal() instanceof AppOAuth2User appUser && appUser.getDisplayName() != null) {
            return appUser.getDisplayName();
        }
        if (authentication.getPrincipal() instanceof OAuth2User oauth2User
                && oauth2User.getAttribute("name") instanceof String name
                && !name.isBlank()) {
            return name;
        }
        return authentication.getName();
    }
}

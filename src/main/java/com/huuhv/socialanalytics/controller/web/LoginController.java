package com.huuhv.socialanalytics.controller.web;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Trang đăng nhập (public). Thông báo {@code ?logout} / {@code ?error} do template hiển thị bằng thông điệp cố định.
 */
@Controller
public class LoginController {

    /**
     * @param authentication {@code null} khi chưa đăng nhập (request ẩn danh không có principal)
     */
    @GetMapping("/login")
    public String login(Authentication authentication) {
        return authentication != null ? "redirect:/dashboard" : "login";
    }
}

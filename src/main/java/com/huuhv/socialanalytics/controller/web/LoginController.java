package com.huuhv.socialanalytics.controller.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Trang đăng nhập (public). Thông báo {@code ?logout} / {@code ?error} do template hiển thị bằng thông điệp cố định.
 */
@Controller
public class LoginController {

    @GetMapping("/login")
    public String login() {
        return "login";
    }
}

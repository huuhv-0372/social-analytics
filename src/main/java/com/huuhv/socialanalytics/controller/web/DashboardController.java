package com.huuhv.socialanalytics.controller.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Trang chính sau khi đăng nhập. Biểu đồ / "Last updated time" bổ sung ở giai đoạn 4 và 6.
 */
@Controller
public class DashboardController {

    @GetMapping("/")
    public String home() {
        return "redirect:/dashboard";
    }

    @GetMapping("/dashboard")
    public String dashboard() {
        return "dashboard";
    }
}

package com.huuhv.socialanalytics.controller.web;

import com.huuhv.socialanalytics.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Trang chính sau khi đăng nhập: "Last updated time" của metrics. Biểu đồ bổ sung ở giai đoạn 6.
 */
@Controller
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/")
    public String home() {
        return "redirect:/dashboard";
    }

    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        model.addAttribute("lastUpdatedAt", dashboardService.findLastUpdatedAt().orElse(null));
        return "dashboard";
    }
}

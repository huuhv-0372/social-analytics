package com.huuhv.socialanalytics.controller.web;

import com.huuhv.socialanalytics.dto.response.DashboardResponse;
import com.huuhv.socialanalytics.service.DashboardService;
import com.huuhv.socialanalytics.service.ExchangeRateService;
import com.huuhv.socialanalytics.websocket.StompDestinations;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Trang chính sau khi đăng nhập: "Last updated time" của metrics + thống kê import tổng hợp + tỷ giá (SOAP) render sẵn
 * lúc tải trang.
 * Biểu đồ và cập nhật realtime do {@code static/js/dashboard.js} làm phía trình duyệt ({@code GET /api/chart-data} +
 * SUBSCRIBE đích STOMP riêng của user {@link StompDestinations#USER_METRICS_DESTINATION}); controller chỉ truyền đích
 * đó qua model ({@code metricsDestination}) để JS không hardcode.
 */
@Controller
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;
    private final ExchangeRateService exchangeRateService;

    @GetMapping("/")
    public String home() {
        return "redirect:/dashboard";
    }

    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        DashboardResponse dashboard = dashboardService.getDashboard();
        model.addAttribute("lastUpdatedAt", dashboard.lastUpdatedAt());
        model.addAttribute("importStatistics", dashboard.importStatistics());
        model.addAttribute("zone", dashboard.zone());
        // Không phụ thuộc user; null = không lấy được (service không ném lỗi → SOAP hỏng không làm hỏng trang)
        model.addAttribute("exchangeRate", exchangeRateService.getCurrentRate());
        // Đích STOMP cho dashboard.js (hằng số dùng chung với server, không hardcode trong JS)
        model.addAttribute("metricsDestination", StompDestinations.USER_METRICS_DESTINATION);
        return "dashboard";
    }
}

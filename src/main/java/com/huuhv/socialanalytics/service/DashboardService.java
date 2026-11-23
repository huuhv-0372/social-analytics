package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.dto.response.DashboardResponse;
import com.huuhv.socialanalytics.dto.response.ImportStatisticsResponse;
import com.huuhv.socialanalytics.util.UtcDateTimes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * Dữ liệu hiển thị trên trang {@code /dashboard} của user đang đăng nhập.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final CurrentUserService currentUserService;
    private final SocialAccountService socialAccountService;
    private final ImportStatisticsService importStatisticsService;
    private final Clock clock;

    /**
     * Owner lấy 1 lần từ {@link CurrentUserService} (fail closed: session không map được user →
     * {@code AuthenticationRequiredException}); mọi số liệu chỉ thuộc user đó. Mốc thời gian trả dạng {@code Instant}
     * kèm múi giờ server ({@link Clock#getZone()}) để template hiển thị — cùng DTO thống kê với
     * {@code GET /api/chart-data}.
     * <ul>
     *     <li>"Last updated time": lần job cập nhật metrics thành công gần nhất trong các tài khoản của user.</li>
     *     <li>Thống kê import tổng hợp (cập nhật bất đồng bộ bởi listener {@code IMPORT_COMPLETED}).</li>
     * </ul>
     */
    public DashboardResponse getDashboard() {
        return loadSummary(currentUserService.getCurrentUser().getId());
    }

    /**
     * Số liệu dashboard của 1 owner đã xác định — dùng chung với {@link ChartDataService} để trang render bằng
     * Thymeleaf và dữ liệu realtime luôn tính theo cùng 1 quy tắc.
     */
    DashboardResponse loadSummary(Long userId) {
        Instant lastUpdatedAt = socialAccountService.findLastMetricsUpdatedAt(userId)
                .map(UtcDateTimes::toInstant)
                .orElse(null);
        ImportStatisticsResponse importStatistics = importStatisticsService.findByUserId(userId)
                .map(ImportStatisticsResponse::from)
                .orElse(null);
        return new DashboardResponse(lastUpdatedAt, importStatistics, clock.getZone());
    }
}

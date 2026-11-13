package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.dto.response.DashboardResponse;
import com.huuhv.socialanalytics.dto.response.ImportStatisticsResponse;
import com.huuhv.socialanalytics.util.UtcDateTimes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;

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
     * {@code AuthenticationRequiredException}); mọi số liệu chỉ thuộc user đó. Giờ UTC trong DB đổi sang múi giờ của
     * server ({@link Clock#getZone()}).
     * <ul>
     *     <li>"Last updated time": lần job cập nhật metrics thành công gần nhất trong các tài khoản của user.</li>
     *     <li>Thống kê import tổng hợp (cập nhật bất đồng bộ bởi listener {@code IMPORT_COMPLETED}).</li>
     * </ul>
     */
    public DashboardResponse getDashboard() {
        Long userId = currentUserService.getCurrentUser().getId();
        ZoneId zone = clock.getZone();
        ZonedDateTime lastUpdatedAt = socialAccountService.findLastMetricsUpdatedAt(userId)
                .map(utc -> UtcDateTimes.atZone(utc, zone))
                .orElse(null);
        ImportStatisticsResponse importStatistics = importStatisticsService.findByUserId(userId)
                .map(statistic -> ImportStatisticsResponse.from(statistic, zone))
                .orElse(null);
        return new DashboardResponse(lastUpdatedAt, importStatistics);
    }
}

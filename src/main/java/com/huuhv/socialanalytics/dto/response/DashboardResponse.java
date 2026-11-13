package com.huuhv.socialanalytics.dto.response;

import java.time.ZonedDateTime;

/**
 * Dữ liệu trang {@code /dashboard} của user đang đăng nhập.
 *
 * @param lastUpdatedAt    lần job cập nhật metrics thành công gần nhất (múi giờ server); {@code null} = chưa cập nhật
 * @param importStatistics thống kê import tổng hợp; {@code null} = chưa có lần import nào được tổng hợp
 */
public record DashboardResponse(ZonedDateTime lastUpdatedAt, ImportStatisticsResponse importStatistics) {
}

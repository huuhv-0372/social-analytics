package com.huuhv.socialanalytics.dto.response;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Dữ liệu trang {@code /dashboard} của user đang đăng nhập.
 *
 * @param lastUpdatedAt    lần job cập nhật metrics thành công gần nhất; {@code null} = chưa cập nhật
 * @param importStatistics thống kê import tổng hợp; {@code null} = chưa có lần import nào được tổng hợp
 * @param zone             múi giờ server — template hiển thị các mốc thời gian theo múi giờ này
 */
public record DashboardResponse(Instant lastUpdatedAt, ImportStatisticsResponse importStatistics, ZoneId zone) {
}

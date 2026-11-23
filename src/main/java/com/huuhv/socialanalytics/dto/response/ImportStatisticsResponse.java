package com.huuhv.socialanalytics.dto.response;

import com.huuhv.socialanalytics.entity.UserImportStatistic;
import com.huuhv.socialanalytics.util.UtcDateTimes;

import java.time.Instant;

/**
 * Thống kê import tổng hợp của user — DTO duy nhất cho cả trang {@code /dashboard} (Thymeleaf đổi giờ sang múi giờ
 * server lúc render) lẫn {@code GET /api/chart-data} (dashboard cập nhật realtime): thêm field ở đây là cả 2 nơi cùng có.
 *
 * @param totalImports số lần import đã được tổng hợp
 * @param successRows  số dòng đã lưu (tạo mới + cập nhật) qua mọi lần import
 * @param lastImportAt lần import mới nhất đã được tổng hợp
 */
public record ImportStatisticsResponse(long totalImports, long totalRows, long successRows, long failedRows,
                                       Instant lastImportAt) {

    /** {@code last_import_at} lưu giờ UTC. */
    public static ImportStatisticsResponse from(UserImportStatistic statistic) {
        return new ImportStatisticsResponse(statistic.getTotalImports(), statistic.getTotalRows(),
                statistic.getSuccessRows(), statistic.getFailedRows(),
                UtcDateTimes.toInstant(statistic.getLastImportAt()));
    }
}

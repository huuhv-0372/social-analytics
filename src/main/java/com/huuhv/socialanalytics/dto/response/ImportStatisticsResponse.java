package com.huuhv.socialanalytics.dto.response;

import com.huuhv.socialanalytics.entity.UserImportStatistic;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Thống kê import tổng hợp của user hiển thị trên dashboard.
 *
 * @param totalImports số lần import đã được tổng hợp
 * @param successRows  số dòng đã lưu (tạo mới + cập nhật) qua mọi lần import
 * @param lastImportAt lần import mới nhất đã được tổng hợp, theo múi giờ server
 */
public record ImportStatisticsResponse(long totalImports, long totalRows, long successRows, long failedRows,
                                       ZonedDateTime lastImportAt) {

    /** {@code last_import_at} lưu giờ UTC → đổi sang {@code zone}. */
    public static ImportStatisticsResponse from(UserImportStatistic statistic, ZoneId zone) {
        return new ImportStatisticsResponse(statistic.getTotalImports(), statistic.getTotalRows(),
                statistic.getSuccessRows(), statistic.getFailedRows(),
                statistic.getLastImportAt().atZone(ZoneOffset.UTC).withZoneSameInstant(zone));
    }
}

package com.huuhv.socialanalytics.dto.response;

import com.huuhv.socialanalytics.enums.Provider;
import com.huuhv.socialanalytics.excel.ExcelColumn;
import com.huuhv.socialanalytics.util.UtcDateTimes;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 1 dòng file export snapshot metric (projection {@code SELECT new}, không load Entity). Cùng tên cột với file import
 * ({@code SocialMetricImportRow}) → sửa rồi import lại được; import lại nguyên file sẽ tạo snapshot trùng (snapshot là
 * bản ghi theo thời gian, không upsert).
 * <p>
 * Query trả {@code collectedAt} theo giờ UTC (như DB); trước khi ghi file đổi sang múi giờ của server bằng
 * {@link #inZone(ZoneId)}.
 */
public record SocialMetricReportRow(
        @ExcelColumn(name = "Provider", order = 1) Provider provider,
        @ExcelColumn(name = "External ID", order = 2) String externalId,
        @ExcelColumn(name = "Likes", order = 3) Long likes,
        @ExcelColumn(name = "Shares", order = 4) Long shares,
        @ExcelColumn(name = "Comments", order = 5) Long comments,
        @ExcelColumn(name = "Followers", order = 6) Long followers,
        @ExcelColumn(name = "Collected At", order = 7) LocalDateTime collectedAt) {

    /** Bản sao với {@code collectedAt} đổi từ giờ UTC sang giờ theo {@code zone}. */
    public SocialMetricReportRow inZone(ZoneId zone) {
        return new SocialMetricReportRow(provider, externalId, likes, shares, comments, followers,
                collectedAt == null ? null : LocalDateTime.ofInstant(UtcDateTimes.toInstant(collectedAt), zone));
    }
}

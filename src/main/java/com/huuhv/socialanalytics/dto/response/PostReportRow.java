package com.huuhv.socialanalytics.dto.response;

import com.huuhv.socialanalytics.enums.Provider;
import com.huuhv.socialanalytics.excel.ExcelColumn;

import java.time.LocalDateTime;

/**
 * 1 dòng báo cáo export: bài viết + snapshot metric mới nhất (projection {@code SELECT new}, không load Entity).
 * 5 cột đầu trùng tên cột của file import ({@code PostImportRow}) → file export import lại được.
 * Bài chưa có snapshot thì các cột metric để trống.
 */
public record PostReportRow(
        @ExcelColumn(name = "Provider", order = 1) Provider provider,
        @ExcelColumn(name = "External ID", order = 2) String externalId,
        @ExcelColumn(name = "URL", order = 3) String url,
        @ExcelColumn(name = "Content", order = 4) String content,
        @ExcelColumn(name = "Published At", order = 5) LocalDateTime publishedAt,
        @ExcelColumn(name = "Likes", order = 6) Long likes,
        @ExcelColumn(name = "Shares", order = 7) Long shares,
        @ExcelColumn(name = "Comments", order = 8) Long comments,
        @ExcelColumn(name = "Followers", order = 9) Long followers,
        @ExcelColumn(name = "Metrics Collected At", order = 10) LocalDateTime metricsCollectedAt) {
}

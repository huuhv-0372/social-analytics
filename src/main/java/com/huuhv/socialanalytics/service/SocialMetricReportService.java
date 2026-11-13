package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.config.properties.AppProperties;
import com.huuhv.socialanalytics.dto.response.ExportFile;
import com.huuhv.socialanalytics.dto.response.SocialMetricReportRow;
import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.excel.GenericExcelExporter;
import com.huuhv.socialanalytics.exception.BadRequestException;
import com.huuhv.socialanalytics.exception.TooManyRequestsException;
import com.huuhv.socialanalytics.util.UtcDateTimes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Export snapshot metric của user đang đăng nhập ra .xlsx (model thứ 2 dùng chung exporter generic với báo cáo bài
 * viết). Cùng giới hạn với {@link PostReportService}: {@code app.export.max-rows} (vượt → 400) và tần suất
 * {@code app.export.min-interval} (bucket riêng, action {@value #RATE_LIMIT_ACTION} → 429).
 * <p>
 * Cố ý không đặt {@code @Transactional}: dữ liệu đọc trong transaction read-only của
 * {@link SocialMetricService#findReportRows}, file được ghi sau đó (lúc controller stream response) nên không giữ
 * connection DB trong lúc dựng file.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SocialMetricReportService {

    static final String RATE_LIMIT_ACTION = "export-metrics";
    private static final String SHEET_NAME = "Metrics";
    private static final DateTimeFormatter FILENAME_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final SocialMetricService socialMetricService;
    private final CurrentUserService currentUserService;
    private final UserRateLimiter userRateLimiter;
    private final GenericExcelExporter excelExporter;
    private final AppProperties appProperties;
    private final Clock clock;

    /**
     * @param from / to khoảng {@code collectedAt} (mốc tuyệt đối, 2 đầu tính cả); {@code null} = không giới hạn
     * @throws BadRequestException      khi {@code from > to} hoặc số snapshot vượt {@code app.export.max-rows}
     * @throws TooManyRequestsException khi user export lại trong vòng {@code app.export.min-interval}
     */
    public ExportFile exportMetrics(Instant from, Instant to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BadRequestException("from must not be after to");
        }
        User owner = currentUserService.getCurrentUser();
        AppProperties.ExportSettings settings = appProperties.exportSettings();
        // Chặn tần suất trước khi chạy query + dựng file (OWASP A06)
        userRateLimiter.acquire(RATE_LIMIT_ACTION, owner.getId(), settings.minInterval());
        int maxRows = settings.maxRows();
        // Lấy dư 1 dòng để biết có vượt giới hạn hay không mà không cần thêm câu COUNT
        List<SocialMetricReportRow> rows = socialMetricService.findReportRows(owner.getId(), UtcDateTimes.toUtc(from),
                UtcDateTimes.toUtc(to), maxRows + 1);
        if (rows.size() > maxRows) {
            log.warn("User {} metrics export rejected: more than {} rows", owner.getId(), maxRows);
            throw new BadRequestException("Report exceeds the maximum of " + maxRows
                    + " rows — narrow the from/to range");
        }

        // DB lưu giờ UTC; ô Excel không có múi giờ → ghi theo múi giờ của server (file import hiểu cùng quy ước)
        List<SocialMetricReportRow> localRows = rows.stream().map(row -> row.inZone(clock.getZone())).toList();

        String filename = "metrics-report-" + LocalDateTime.now(clock).format(FILENAME_TIMESTAMP) + ".xlsx";
        Long ownerId = owner.getId();
        return new ExportFile(filename, out -> {
            excelExporter.write(localRows, SocialMetricReportRow.class, SHEET_NAME, out);
            // Audit (OWASP A09): ai export, bao nhiêu dòng — ghi sau khi file đã ghi xong
            log.info("User {} exported metrics report: rows={}", ownerId, localRows.size());
        });
    }
}

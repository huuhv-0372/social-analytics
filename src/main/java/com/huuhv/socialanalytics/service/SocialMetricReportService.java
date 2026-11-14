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
 * {@code app.export.min-interval} (bucket riêng, action {@value #RATE_LIMIT_ACTION} → 429). Khác báo cáo bài viết
 * (số dòng = số bài, cố định), số snapshot tăng theo thời gian và phụ thuộc from/to → giới hạn dòng được kiểm tra bằng
 * câu đếm rẻ <b>trước</b> khi tính tần suất: bị 400 vì khoảng quá rộng không tốn lượt export.
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
        int maxRows = settings.maxRows();
        LocalDateTime fromUtc = UtcDateTimes.toUtc(from);
        LocalDateTime toUtc = UtcDateTimes.toUtc(to);
        // Kiểm tra giới hạn dòng TRƯỚC khi tính tần suất: khoảng quá rộng → 400 mà không tốn lượt export, user thu hẹp
        // from/to rồi gửi lại ngay không bị 429. Câu đếm rẻ (dừng ở maxRows + 1, không sắp xếp) nên không cần chặn
        // tần suất; câu query có filesort bên dưới chỉ chạy khi số dòng nằm trong giới hạn.
        if (socialMetricService.countReportRowsUpTo(owner.getId(), fromUtc, toUtc, maxRows + 1) > maxRows) {
            throw tooManyRows(owner.getId(), maxRows);
        }
        // Chặn tần suất trước khi chạy query + dựng file (OWASP A06)
        userRateLimiter.acquire(RATE_LIMIT_ACTION, owner.getId(), settings.minInterval());
        // Vẫn lấy dư 1 dòng: snapshot mới có thể được ghi giữa câu đếm và câu này
        List<SocialMetricReportRow> rows = socialMetricService.findReportRows(owner.getId(), fromUtc, toUtc,
                maxRows + 1);
        if (rows.size() > maxRows) {
            throw tooManyRows(owner.getId(), maxRows);
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

    private static BadRequestException tooManyRows(Long ownerId, int maxRows) {
        log.warn("User {} metrics export rejected: more than {} rows", ownerId, maxRows);
        return new BadRequestException("Report exceeds the maximum of " + maxRows
                + " rows — narrow the from/to range");
    }
}

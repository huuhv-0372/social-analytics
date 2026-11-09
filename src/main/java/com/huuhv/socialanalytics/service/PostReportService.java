package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.config.AppProperties;
import com.huuhv.socialanalytics.dto.response.ExportFile;
import com.huuhv.socialanalytics.dto.response.PostReportRow;
import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.excel.GenericExcelExporter;
import com.huuhv.socialanalytics.exception.BadRequestException;
import com.huuhv.socialanalytics.exception.TooManyRequestsException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Báo cáo bài viết + snapshot metric mới nhất của user đang đăng nhập, xuất ra .xlsx.
 * <p>
 * Cố ý không đặt {@code @Transactional} cho cả method: dữ liệu đọc trong transaction read-only của
 * {@link PostService#findReportRows}, còn bước ghi file chạy sau đó (lúc controller stream response) nên không giữ
 * connection DB trong khi dựng file.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostReportService {

    static final String RATE_LIMIT_ACTION = "export-report";
    private static final String SHEET_NAME = "Posts";
    private static final DateTimeFormatter FILENAME_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final PostService postService;
    private final CurrentUserService currentUserService;
    private final UserRateLimiter userRateLimiter;
    private final GenericExcelExporter excelExporter;
    private final AppProperties appProperties;
    private final Clock clock;

    /**
     * Kiểm tra quyền, tần suất, giới hạn dòng và đọc dữ liệu ngay; nội dung file được ghi khi gọi
     * {@link ExportFile.Content#writeTo}.
     *
     * @throws TooManyRequestsException khi user export lại trong vòng {@code app.export.min-interval}
     * @throws BadRequestException      khi số bài vượt {@code app.export.max-rows}
     */
    public ExportFile exportReport() {
        User owner = currentUserService.getCurrentUser();
        AppProperties.ExportSettings settings = appProperties.exportSettings();
        // Chặn tần suất trước khi chạy query + dựng file (OWASP A06)
        userRateLimiter.acquire(RATE_LIMIT_ACTION, owner.getId(), settings.minInterval());
        int maxRows = settings.maxRows();
        // Lấy dư 1 dòng để biết có vượt giới hạn hay không mà không cần thêm câu COUNT
        List<PostReportRow> rows = postService.findReportRows(owner.getId(), maxRows + 1);
        if (rows.size() > maxRows) {
            log.warn("User {} export rejected: more than {} rows", owner.getId(), maxRows);
            throw new BadRequestException("Report exceeds the maximum of " + maxRows + " rows");
        }

        // DB lưu giờ UTC; ô Excel không có múi giờ → ghi theo múi giờ của server (file import hiểu cùng quy ước)
        List<PostReportRow> localRows = rows.stream().map(row -> row.inZone(clock.getZone())).toList();

        String filename = "posts-report-" + LocalDateTime.now(clock).format(FILENAME_TIMESTAMP) + ".xlsx";
        Long ownerId = owner.getId();
        return new ExportFile(filename, out -> {
            excelExporter.write(localRows, PostReportRow.class, SHEET_NAME, out);
            // Audit (OWASP A09): ai export, bao nhiêu dòng — ghi sau khi file đã ghi xong
            log.info("User {} exported posts report: rows={}", ownerId, localRows.size());
        });
    }
}

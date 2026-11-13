package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.config.properties.AppProperties;
import com.huuhv.socialanalytics.dto.request.PostImportRow;
import com.huuhv.socialanalytics.dto.request.PostRequest;
import com.huuhv.socialanalytics.dto.response.ImportResultResponse;
import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.excel.ExcelReadResult;
import com.huuhv.socialanalytics.excel.ExcelRow;
import com.huuhv.socialanalytics.excel.ExcelRowError;
import com.huuhv.socialanalytics.exception.BadRequestException;
import com.huuhv.socialanalytics.exception.TooManyRequestsException;
import com.huuhv.socialanalytics.util.SocialUrlPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Import bài viết từ file Excel cho user đang đăng nhập. Dòng lỗi được báo kèm số dòng, dòng hợp lệ vẫn được lưu;
 * bài trùng (provider, externalId) với bài đã có của user thì cập nhật (upsert).
 * <p>
 * Không có transaction bao cả method: parse + validate file chạy ngoài transaction ({@link ExcelImportSupport}), chỉ
 * bước ghi ({@link PostUpsertService#saveImport}) giữ connection DB.
 * <p>
 * Tần suất ({@code app.import.min-interval}, action {@value #RATE_LIMIT_ACTION} → 429) tính ngay trước bước ghi —
 * mỗi lần ghi là 1 lần import (lịch sử + message JMS + thống kê); file bị từ chối cả file (sai định dạng, thiếu cột...)
 * không tốn lượt, user sửa file rồi gửi lại ngay được.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostImportService {

    static final String RATE_LIMIT_ACTION = "import-posts";
    // File mẫu (dữ liệu giả) — PostImportServiceTest đảm bảo file luôn import được, không lệch với PostImportRow
    private static final Resource TEMPLATE = new ClassPathResource("excel/posts-import-template.xlsx");
    private static final String PROVIDER_COLUMN = ExcelImportSupport.column(PostImportRow.class, "provider");
    private static final String URL_COLUMN = ExcelImportSupport.column(PostImportRow.class, "url");
    private static final String EXTERNAL_ID_COLUMN = ExcelImportSupport.column(PostImportRow.class, "externalId");
    private static final Comparator<ExcelRowError> BY_COLUMN_ORDER =
            ExcelImportSupport.byColumnOrder(PostImportRow.class);

    private final ExcelImportSupport excelImportSupport;
    private final PostUpsertService postUpsertService;
    private final CurrentUserService currentUserService;
    private final UserRateLimiter userRateLimiter;
    private final AppProperties appProperties;
    private final Clock clock;

    /**
     * @throws BadRequestException khi file rỗng, không phải .xlsx (đuôi hoặc nội dung), vượt dung lượng giải nén,
     *                             thiếu cột, không có dòng dữ liệu nào (chỉ có header) hoặc quá số dòng
     * @throws TooManyRequestsException khi user vừa import bài viết trong vòng {@code app.import.min-interval}
     */
    public ImportResultResponse importPosts(MultipartFile file) {
        // Fail closed: kiểm tra owner trước khi parse (POI). Request ẩn danh đã bị SecurityConfig chặn từ filter chain,
        // trước khi multipart được đọc — lệnh này là lớp bảo vệ thứ hai.
        User owner = currentUserService.getCurrentUser();
        ExcelReadResult<PostImportRow> result = excelImportSupport.read(file, PostImportRow.class);

        if (result.totalRows() == 0) {
            // File chỉ có dòng tiêu đề: không phải 1 lần import — không ghi lịch sử, không cộng thống kê
            throw new BadRequestException("File has no data rows");
        }

        List<ExcelRowError> errors = new ArrayList<>();
        List<PostRequest> validRows = validate(result.rows(), errors);
        // Chặn tần suất ghi (OWASP A06): mỗi lần ghi tạo 1 lịch sử import + 1 message JMS, kể cả khi mọi dòng lỗi
        userRateLimiter.acquire(RATE_LIMIT_ACTION, owner.getId(), appProperties.importSettings().minInterval());
        PostUpsertService.Result counts = postUpsertService.saveImport(owner, validRows, result.totalRows());

        int created = counts.created();
        int updated = counts.updated();
        int failed = counts.failed();
        // Audit (OWASP A09): ai import, bao nhiêu dòng — không log tên file hay nội dung
        log.info("User {} imported posts: importId={}, total={}, created={}, updated={}, failed={}",
                owner.getId(), counts.importId(), result.totalRows(), created, updated, failed);
        return new ImportResultResponse(counts.importId(), result.totalRows(), created, updated, failed, errors.stream()
                .map(error -> new ImportResultResponse.RowError(error.rowNumber(), error.column(), error.message()))
                .toList());
    }

    /** File .xlsx mẫu đúng định dạng import (header + vài dòng dữ liệu giả + sheet hướng dẫn). */
    public Resource getTemplate() {
        return TEMPLATE;
    }

    /**
     * Lỗi chuyển kiểu + Bean Validation của {@link PostRequest} (cùng quy tắc với REST API) + whitelist URL
     * (chống SSRF) + trùng trong cùng file. Mọi lỗi của 1 dòng được báo cùng lúc; dòng lỗi bị loại.
     *
     * @param errors nhận lỗi theo thứ tự dòng, trong 1 dòng theo thứ tự cột
     */
    private List<PostRequest> validate(List<ExcelRow<PostImportRow>> rows, List<ExcelRowError> errors) {
        Map<String, Integer> firstRowByKey = new HashMap<>();
        List<PostRequest> valid = new ArrayList<>();
        for (ExcelRow<PostImportRow> row : rows) {
            // Ô ngày giờ Excel không có múi giờ → hiểu theo múi giờ của server (như file export)
            PostRequest request = row.value().toPostRequest(clock.getZone());
            List<ExcelRowError> rowErrors = excelImportSupport.validate(row, request);
            boolean providerAndUrlOk = rowErrors.stream()
                    .noneMatch(error -> PROVIDER_COLUMN.equals(error.column()) || URL_COLUMN.equals(error.column()));
            if (providerAndUrlOk && !SocialUrlPolicy.isAllowed(request.provider(), request.url())) {
                rowErrors.add(new ExcelRowError(row.rowNumber(), URL_COLUMN, SocialUrlPolicy.VIOLATION_MESSAGE));
            }
            if (rowErrors.isEmpty()) {
                String key = PostUpsertService.matchKey(request.provider(), request.externalId());
                Integer firstRow = firstRowByKey.putIfAbsent(key, row.rowNumber());
                if (firstRow != null) {
                    rowErrors.add(new ExcelRowError(row.rowNumber(), EXTERNAL_ID_COLUMN,
                            "duplicate of row " + firstRow));
                }
            }
            if (rowErrors.isEmpty()) {
                valid.add(request);
            } else {
                rowErrors.sort(BY_COLUMN_ORDER);
                errors.addAll(rowErrors);
            }
        }
        return valid;
    }
}

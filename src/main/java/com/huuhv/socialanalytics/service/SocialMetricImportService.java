package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.config.properties.AppProperties;
import com.huuhv.socialanalytics.dto.request.PostRequest;
import com.huuhv.socialanalytics.dto.request.SocialMetricImportRow;
import com.huuhv.socialanalytics.dto.request.SocialMetricRequest;
import com.huuhv.socialanalytics.dto.response.ImportResultResponse;
import com.huuhv.socialanalytics.dto.response.MetricImportResultResponse;
import com.huuhv.socialanalytics.dto.response.PostRef;
import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.enums.Provider;
import com.huuhv.socialanalytics.excel.ExcelReadResult;
import com.huuhv.socialanalytics.excel.ExcelRow;
import com.huuhv.socialanalytics.excel.ExcelRowError;
import com.huuhv.socialanalytics.exception.BadRequestException;
import com.huuhv.socialanalytics.exception.TooManyRequestsException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Import snapshot metric từ file Excel cho user đang đăng nhập (model thứ 2 dùng chung importer generic với bài viết).
 * Mỗi dòng xác định bài viết bằng (Provider, External ID) <b>trong các bài của owner</b> — bài của user khác hoặc
 * không tồn tại đều báo cùng 1 lỗi "post not found" (không lộ bài của người khác — chống IDOR). Số liệu validate bằng
 * {@link SocialMetricRequest} (cùng quy tắc với {@code POST /api/metrics}). Dòng lỗi được báo kèm số dòng, dòng hợp lệ
 * vẫn được lưu; trùng (bài, Collected At) trong cùng file → lỗi ở dòng sau; snapshot đã có trong DB (cùng bài, cùng
 * Collected At tới mili giây — độ chính xác của ô Excel) → lỗi, không ghi lại: import lại cùng file / file export
 * không làm bảng {@code social_metrics} phình ra.
 * <p>
 * External ID phải đúng định dạng id bài ({@link PostRequest#isValidExternalId}) trước khi tra DB: trong tập ký tự đó
 * so khớp không phân biệt hoa thường ở Java cho cùng kết quả với collation của DB.
 * <p>
 * Parse + validate + tra bài chạy ngoài transaction; chỉ bước ghi ({@link SocialMetricService#importSnapshots}) giữ
 * connection. Chỉ khi có dòng cần ghi mới tính tần suất ({@code app.import.min-interval}, action
 * {@value #RATE_LIMIT_ACTION} → 429) — file toàn lỗi không làm user phải chờ khi gửi lại file đã sửa. Không ghi lịch
 * sử / thống kê import (chỉ dành cho import bài viết); dashboard của owner được báo làm mới.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SocialMetricImportService {

    static final String POST_NOT_FOUND = "post not found";
    static final String SNAPSHOT_EXISTS = "snapshot already exists";
    static final String RATE_LIMIT_ACTION = "import-metrics";
    // File mẫu (dữ liệu giả, External ID trùng các bài demo-* của posts-import-template.xlsx) — SocialMetricImportServiceTest
    // đảm bảo file luôn import được sau khi import file mẫu bài viết, không lệch với SocialMetricImportRow
    private static final Resource TEMPLATE = new ClassPathResource("excel/metrics-import-template.xlsx");
    private static final String PROVIDER_COLUMN = ExcelImportSupport.column(SocialMetricImportRow.class, "provider");
    private static final String EXTERNAL_ID_COLUMN =
            ExcelImportSupport.column(SocialMetricImportRow.class, "externalId");
    private static final String COLLECTED_AT_COLUMN =
            ExcelImportSupport.column(SocialMetricImportRow.class, "collectedAt");
    private static final Comparator<ExcelRowError> BY_COLUMN_ORDER =
            ExcelImportSupport.byColumnOrder(SocialMetricImportRow.class);
    /** {@code postId} không có cột trong file — Service tự tra và tự báo lỗi ở cột External ID. */
    private static final Set<String> SERVICE_FILLED_PROPERTIES = Set.of("postId");

    private final ExcelImportSupport excelImportSupport;
    private final PostService postService;
    private final SocialMetricService socialMetricService;
    private final CurrentUserService currentUserService;
    private final UserRateLimiter userRateLimiter;
    private final AppProperties appProperties;
    private final Clock clock;

    /**
     * @throws BadRequestException      khi file rỗng, không phải .xlsx, vượt giới hạn, thiếu cột hoặc không có dòng dữ
     *                                  liệu
     * @throws TooManyRequestsException khi file có snapshot cần ghi nhưng user vừa import metric trong vòng
     *                                  {@code app.import.min-interval}
     */
    public MetricImportResultResponse importMetrics(MultipartFile file) {
        // Fail closed: xác định owner trước khi parse file
        User owner = currentUserService.getCurrentUser();
        ExcelReadResult<SocialMetricImportRow> result = excelImportSupport.read(file, SocialMetricImportRow.class);
        if (result.totalRows() == 0) {
            throw new BadRequestException("File has no data rows");
        }

        Map<String, Long> postIdByKey = findOwnedPosts(owner.getId(), result.rows());
        List<ExcelRowError> errors = new ArrayList<>();
        List<ValidRow> validRows = validate(result.rows(), postIdByKey, errors);
        List<SocialMetricRequest> valid = withoutExistingSnapshots(validRows, errors);
        if (!valid.isEmpty()) {
            // Chặn tần suất ghi (OWASP A06) — chỉ khi thực sự có snapshot cần ghi
            userRateLimiter.acquire(RATE_LIMIT_ACTION, owner.getId(), appProperties.importSettings().minInterval());
        }
        int imported = socialMetricService.importSnapshots(owner.getId(), valid);
        int failed = result.totalRows() - imported;

        // Audit (OWASP A09): ai import, bao nhiêu dòng — không log tên file hay nội dung
        log.info("User {} imported metric snapshots: total={}, imported={}, failed={}", owner.getId(),
                result.totalRows(), imported, failed);
        return new MetricImportResultResponse(result.totalRows(), imported, failed, errors.stream()
                .map(error -> new ImportResultResponse.RowError(error.rowNumber(), error.column(), error.message()))
                .toList());
    }

    /** File .xlsx mẫu đúng định dạng import (header + snapshot giả của các bài demo-* + sheet hướng dẫn). */
    public Resource getTemplate() {
        return TEMPLATE;
    }

    /** Tra bài của owner theo lô: 1 query (mỗi lô) cho mỗi provider, không query từng dòng. */
    private Map<String, Long> findOwnedPosts(Long ownerId, List<ExcelRow<SocialMetricImportRow>> rows) {
        Map<Provider, Set<String>> externalIdsByProvider = new EnumMap<>(Provider.class);
        for (ExcelRow<SocialMetricImportRow> row : rows) {
            SocialMetricImportRow value = row.value();
            // Chỉ tra id đúng định dạng: ngoài tập đó collation của DB khớp cả giá trị mà matchKey coi là khác
            if (value.getProvider() != null && PostRequest.isValidExternalId(value.getExternalId())) {
                externalIdsByProvider.computeIfAbsent(value.getProvider(), provider -> new LinkedHashSet<>())
                        .add(value.getExternalId());
            }
        }
        Map<String, Long> postIdByKey = new HashMap<>();
        externalIdsByProvider.forEach((provider, externalIds) -> {
            for (PostRef ref : postService.findOwnedPostRefs(ownerId, provider, externalIds)) {
                postIdByKey.put(PostUpsertService.matchKey(provider, ref.externalId()), ref.id());
            }
        });
        return postIdByKey;
    }

    /**
     * @param errors nhận lỗi theo thứ tự dòng, trong 1 dòng theo thứ tự cột
     */
    private List<ValidRow> validate(List<ExcelRow<SocialMetricImportRow>> rows, Map<String, Long> postIdByKey,
                                    List<ExcelRowError> errors) {
        Map<String, Integer> firstRowBySnapshot = new HashMap<>();
        List<ValidRow> valid = new ArrayList<>();
        for (ExcelRow<SocialMetricImportRow> row : rows) {
            SocialMetricImportRow value = row.value();
            Long postId = value.getProvider() == null || !PostRequest.isValidExternalId(value.getExternalId()) ? null
                    : postIdByKey.get(PostUpsertService.matchKey(value.getProvider(), value.getExternalId()));
            // Ô ngày giờ Excel không có múi giờ → hiểu theo múi giờ của server (như file export)
            SocialMetricRequest request = value.toRequest(postId, clock.getZone());
            List<ExcelRowError> rowErrors = excelImportSupport.validate(row, request, SERVICE_FILLED_PROPERTIES);
            boolean postColumnsReadable = rowErrors.stream().noneMatch(
                    error -> PROVIDER_COLUMN.equals(error.column()) || EXTERNAL_ID_COLUMN.equals(error.column()));
            if (postColumnsReadable && postId == null) {
                // 2 cột này không có trong SocialMetricRequest → Service tự kiểm tra
                rowErrors.addAll(postColumnErrors(row));
            }
            if (rowErrors.isEmpty()) {
                String snapshotKey = request.postId() + "@" + request.collectedAt();
                Integer firstRow = firstRowBySnapshot.putIfAbsent(snapshotKey, row.rowNumber());
                if (firstRow != null) {
                    rowErrors.add(new ExcelRowError(row.rowNumber(), EXTERNAL_ID_COLUMN,
                            "duplicate of row " + firstRow));
                }
            }
            if (rowErrors.isEmpty()) {
                valid.add(new ValidRow(row.rowNumber(), request));
            } else {
                rowErrors.sort(BY_COLUMN_ORDER);
                errors.addAll(rowErrors);
            }
        }
        return valid;
    }

    /**
     * Bỏ các dòng có snapshot đã tồn tại trong DB (1 query cho cả file), báo lỗi ở cột Collected At.
     *
     * @param errors lỗi theo thứ tự dòng — lỗi mới được xếp vào đúng vị trí dòng
     * @return request cần ghi, theo thứ tự dòng
     */
    private List<SocialMetricRequest> withoutExistingSnapshots(List<ValidRow> validRows, List<ExcelRowError> errors) {
        List<SocialMetricRequest> requests = validRows.stream().map(ValidRow::request).toList();
        Set<Integer> existing = socialMetricService.findExistingSnapshotIndexes(requests);
        if (existing.isEmpty()) {
            return requests;
        }
        List<SocialMetricRequest> remaining = new ArrayList<>(requests.size());
        for (int i = 0; i < validRows.size(); i++) {
            if (existing.contains(i)) {
                errors.add(new ExcelRowError(validRows.get(i).rowNumber(), COLLECTED_AT_COLUMN, SNAPSHOT_EXISTS));
            } else {
                remaining.add(requests.get(i));
            }
        }
        // Sắp ổn định theo dòng: lỗi trong cùng 1 dòng giữ thứ tự cột
        errors.sort(Comparator.comparingInt(ExcelRowError::rowNumber));
        return remaining;
    }

    /**
     * Dòng không tra được bài: thiếu Provider, External ID trống / sai định dạng id bài, hoặc không có bài đó trong các
     * bài của owner.
     */
    private static List<ExcelRowError> postColumnErrors(ExcelRow<SocialMetricImportRow> row) {
        SocialMetricImportRow value = row.value();
        List<ExcelRowError> errors = new ArrayList<>(2);
        if (value.getProvider() == null) {
            errors.add(new ExcelRowError(row.rowNumber(), PROVIDER_COLUMN, "must not be null"));
        }
        if (value.getExternalId() == null || value.getExternalId().isBlank()) {
            errors.add(new ExcelRowError(row.rowNumber(), EXTERNAL_ID_COLUMN, "must not be blank"));
        } else if (value.getExternalId().length() > PostRequest.EXTERNAL_ID_MAX_LENGTH) {
            // Cùng quy tắc (và thông điệp) với Bean Validation của id bài (PostRequest)
            errors.add(new ExcelRowError(row.rowNumber(), EXTERNAL_ID_COLUMN,
                    "size must be between 0 and " + PostRequest.EXTERNAL_ID_MAX_LENGTH));
        } else if (!PostRequest.isValidExternalId(value.getExternalId())) {
            errors.add(new ExcelRowError(row.rowNumber(), EXTERNAL_ID_COLUMN, PostRequest.EXTERNAL_ID_MESSAGE));
        }
        if (errors.isEmpty()) {
            errors.add(new ExcelRowError(row.rowNumber(), EXTERNAL_ID_COLUMN, POST_NOT_FOUND));
        }
        return errors;
    }

    /** Dòng đã qua mọi kiểm tra trong file, chờ đối chiếu với snapshot đã có trong DB. */
    private record ValidRow(int rowNumber, SocialMetricRequest request) {
    }
}

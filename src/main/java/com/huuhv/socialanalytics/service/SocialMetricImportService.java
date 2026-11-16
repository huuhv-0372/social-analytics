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
import com.huuhv.socialanalytics.exception.DuplicateResourceException;
import com.huuhv.socialanalytics.exception.TooManyRequestsException;
import com.huuhv.socialanalytics.service.SocialMetricService.SnapshotImport;
import com.huuhv.socialanalytics.service.SocialMetricService.SnapshotKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Import snapshot metric từ file Excel cho user đang đăng nhập (model thứ 2 dùng chung importer generic với bài viết).
 * Mỗi dòng xác định bài viết bằng (Provider, External ID) <b>trong các bài của owner</b> — bài của user khác hoặc
 * không tồn tại đều báo cùng 1 lỗi "post not found" (không lộ bài của người khác — chống IDOR). Số liệu validate bằng
 * {@link SocialMetricRequest} (cùng quy tắc với {@code POST /api/metrics}). Dòng lỗi được báo kèm số dòng, dòng hợp lệ
 * vẫn được lưu; mọi dòng tính vào {@code failed} đều có lỗi kèm số dòng. Trùng (bài, Collected At) trong cùng file →
 * lỗi ở dòng sau; snapshot đã có trong DB (cùng bài, cùng Collected At tới mili giây — độ chính xác của ô Excel) →
 * lỗi, không ghi lại: import lại cùng file / file export không làm bảng {@code social_metrics} phình ra. Bài bị xoá
 * giữa lúc tra và lúc ghi → dòng của bài đó báo "post not found".
 * <p>
 * Ô Collected At không có múi giờ → hiểu theo múi giờ nghiệp vụ ({@code app.time-zone}, bean {@link Clock}). Giờ
 * local lặp lại khi lùi đồng hồ (DST) ứng với 2 thời điểm: dòng được ghi theo offset sớm hơn
 * ({@link SocialMetricImportRow#toRequest}) nhưng kiểm tra "đã có" với <b>cả 2</b> thời điểm, nên file export import
 * lại không sinh snapshot trùng lệch 1 giờ. Giới hạn: trong giờ lặp lại, 2 dòng cùng bài cùng giờ local bị coi là trùng
 * nhau (múi giờ mặc định {@code Asia/Ho_Chi_Minh} không có DST).
 * <p>
 * External ID phải đúng định dạng id bài ({@link PostRequest#isValidExternalId}) trước khi tra DB: trong tập ký tự đó
 * so khớp không phân biệt hoa thường ở Java cho cùng kết quả với collation của DB. Khoá tra bài tính 1 lần cho mỗi
 * dòng ({@link #lookupKey}) và dùng cho cả bước tra lẫn bước ghép kết quả.
 * <p>
 * Parse + validate + tra bài chạy ngoài transaction; chỉ bước ghi ({@link SocialMetricService#importSnapshots}) giữ
 * connection. Chỉ khi có dòng cần ghi mới tính tần suất ({@code app.import.min-interval}, action
 * {@value #RATE_LIMIT_ACTION} → 429) — file toàn lỗi không làm user phải chờ khi gửi lại file đã sửa. Request khác ghi
 * cùng (bài, thời điểm) xen giữa lúc kiểm tra và lúc ghi (nhiều instance, {@code min-interval = 0},
 * {@code POST /api/metrics}) → unique index chặn, cả file không được ghi → 409. Không ghi lịch sử / thống kê import
 * (chỉ dành cho import bài viết); dashboard của owner được báo làm mới.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SocialMetricImportService {

    static final String POST_NOT_FOUND = "post not found";
    static final String SNAPSHOT_EXISTS = "snapshot already exists";
    static final String CONCURRENT_WRITE_MESSAGE = "Some snapshots of the file were saved by another request at the"
            + " same time; nothing was imported, send the file again";
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
    /** Sắp ổn định theo dòng: lỗi trong cùng 1 dòng giữ thứ tự cột đã sắp. */
    private static final Comparator<ExcelRowError> BY_ROW = Comparator.comparingInt(ExcelRowError::rowNumber);
    private static final SnapshotImport NOTHING_SAVED = new SnapshotImport(0, Set.of());
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
     * @throws BadRequestException        khi file rỗng, không phải .xlsx, vượt giới hạn, thiếu cột hoặc không có dòng
     *                                    dữ liệu
     * @throws TooManyRequestsException   khi file có snapshot cần ghi nhưng user vừa import metric trong vòng
     *                                    {@code app.import.min-interval}
     * @throws DuplicateResourceException khi request khác ghi cùng (bài, thời điểm) trong lúc import — không dòng nào
     *                                    được ghi, gửi lại file thì dòng đó báo {@value #SNAPSHOT_EXISTS}
     */
    public MetricImportResultResponse importMetrics(MultipartFile file) {
        // Fail closed: xác định owner trước khi parse file
        User owner = currentUserService.getCurrentUser();
        ExcelReadResult<SocialMetricImportRow> result = excelImportSupport.read(file, SocialMetricImportRow.class);
        if (result.totalRows() == 0) {
            throw new BadRequestException("File has no data rows");
        }

        List<ExcelRow<SocialMetricImportRow>> rows = result.rows();
        // Khoá tra bài của từng dòng, tính 1 lần — bước tra DB và bước ghép kết quả dùng cùng 1 giá trị
        List<String> lookupKeys = rows.stream().map(row -> lookupKey(row.value())).toList();
        Map<String, Long> postIdByKey = findOwnedPosts(owner.getId(), rows, lookupKeys);
        List<ExcelRowError> errors = new ArrayList<>();
        List<ValidRow> validRows = validate(rows, lookupKeys, postIdByKey, errors);
        List<ValidRow> toSave = withoutExistingSnapshots(validRows, errors);
        SnapshotImport saved = NOTHING_SAVED;
        if (!toSave.isEmpty()) {
            // Chặn tần suất ghi (OWASP A06) — chỉ khi thực sự có snapshot cần ghi
            userRateLimiter.acquire(RATE_LIMIT_ACTION, owner.getId(), appProperties.importSettings().minInterval());
            saved = save(owner.getId(), toSave);
        }
        addDeletedPostErrors(toSave, saved.deletedPostIds(), errors);
        errors.sort(BY_ROW);
        int imported = saved.saved();
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

    /**
     * Khoá tra bài của 1 dòng; {@code null} khi thiếu Provider hoặc External ID sai định dạng id bài — không tra DB:
     * ngoài tập ký tự của id bài, collation của DB khớp cả giá trị mà {@link PostUpsertService#matchKey} coi là khác.
     */
    private static String lookupKey(SocialMetricImportRow value) {
        return value.getProvider() != null && PostRequest.isValidExternalId(value.getExternalId())
                ? PostUpsertService.matchKey(value.getProvider(), value.getExternalId())
                : null;
    }

    /**
     * Tra bài của owner theo lô: 1 query (mỗi lô) cho mỗi provider, không query từng dòng.
     *
     * @param lookupKeys khoá tra của từng dòng (cùng vị trí với {@code rows})
     */
    private Map<String, Long> findOwnedPosts(Long ownerId, List<ExcelRow<SocialMetricImportRow>> rows,
                                             List<String> lookupKeys) {
        Map<Provider, Set<String>> externalIdsByProvider = new EnumMap<>(Provider.class);
        for (int i = 0; i < rows.size(); i++) {
            if (lookupKeys.get(i) != null) {
                SocialMetricImportRow value = rows.get(i).value();
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
    private List<ValidRow> validate(List<ExcelRow<SocialMetricImportRow>> rows, List<String> lookupKeys,
                                    Map<String, Long> postIdByKey, List<ExcelRowError> errors) {
        ZoneId zone = clock.getZone();
        Map<String, Integer> firstRowBySnapshot = new HashMap<>();
        List<ValidRow> valid = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            ExcelRow<SocialMetricImportRow> row = rows.get(i);
            SocialMetricImportRow value = row.value();
            String lookupKey = lookupKeys.get(i);
            Long postId = lookupKey == null ? null : postIdByKey.get(lookupKey);
            // Ô ngày giờ Excel không có múi giờ → hiểu theo múi giờ nghiệp vụ (như file export)
            SocialMetricRequest request = value.toRequest(postId, zone);
            List<ExcelRowError> rowErrors = excelImportSupport.validate(row, request, SERVICE_FILLED_PROPERTIES);
            boolean postColumnsReadable = rowErrors.stream().noneMatch(
                    error -> PROVIDER_COLUMN.equals(error.column()) || EXTERNAL_ID_COLUMN.equals(error.column()));
            if (postColumnsReadable && postId == null) {
                // 2 cột này không có trong SocialMetricRequest → Service tự kiểm tra
                rowErrors.addAll(postColumnErrors(row));
            }
            if (rowErrors.isEmpty()) {
                String snapshotKey = postId + "@" + SocialMetricService.toSnapshotTime(request.collectedAt());
                Integer firstRow = firstRowBySnapshot.putIfAbsent(snapshotKey, row.rowNumber());
                if (firstRow != null) {
                    rowErrors.add(new ExcelRowError(row.rowNumber(), EXTERNAL_ID_COLUMN,
                            "duplicate of row " + firstRow));
                }
            }
            if (rowErrors.isEmpty()) {
                valid.add(new ValidRow(row.rowNumber(), request, possibleInstants(value.getCollectedAt(), zone)));
            } else {
                rowErrors.sort(BY_COLUMN_ORDER);
                errors.addAll(rowErrors);
            }
        }
        return valid;
    }

    /**
     * Mọi thời điểm ứng với giờ local {@code collectedAt} theo {@code zone}: thời điểm đã dùng cho request (offset sớm
     * hơn — như {@link SocialMetricImportRow#toRequest}), thêm thời điểm theo offset muộn hơn khi giờ đó lặp lại lúc lùi
     * đồng hồ (DST).
     */
    private static List<Instant> possibleInstants(LocalDateTime collectedAt, ZoneId zone) {
        ZonedDateTime earlier = collectedAt.atZone(zone);
        ZonedDateTime later = earlier.withLaterOffsetAtOverlap();
        return earlier.equals(later) ? List.of(earlier.toInstant()) : List.of(earlier.toInstant(), later.toInstant());
    }

    /**
     * Bỏ các dòng có snapshot đã tồn tại trong DB (1 query cho cả file), báo lỗi ở cột Collected At. Dòng có giờ local
     * lặp lại (DST) được đối chiếu với cả 2 thời điểm có thể.
     *
     * @param errors nhận lỗi mới (chưa theo thứ tự dòng — caller sắp 1 lần ở cuối)
     * @return dòng cần ghi, theo thứ tự dòng
     */
    private List<ValidRow> withoutExistingSnapshots(List<ValidRow> validRows, List<ExcelRowError> errors) {
        if (validRows.isEmpty()) {
            return validRows;
        }
        List<SnapshotKey> keys = new ArrayList<>(validRows.size());
        List<Integer> rowIndexByKey = new ArrayList<>(validRows.size());
        for (int i = 0; i < validRows.size(); i++) {
            ValidRow row = validRows.get(i);
            for (Instant candidate : row.possibleCollectedAt()) {
                keys.add(new SnapshotKey(row.request().postId(), candidate));
                rowIndexByKey.add(i);
            }
        }
        Set<Integer> existingRows = new HashSet<>();
        for (int keyIndex : socialMetricService.findExistingSnapshotIndexes(keys)) {
            existingRows.add(rowIndexByKey.get(keyIndex));
        }
        if (existingRows.isEmpty()) {
            return validRows;
        }
        List<ValidRow> remaining = new ArrayList<>(validRows.size() - existingRows.size());
        for (int i = 0; i < validRows.size(); i++) {
            if (existingRows.contains(i)) {
                errors.add(new ExcelRowError(validRows.get(i).rowNumber(), COLLECTED_AT_COLUMN, SNAPSHOT_EXISTS));
            } else {
                remaining.add(validRows.get(i));
            }
        }
        return remaining;
    }

    /**
     * Ghi các dòng còn lại trong 1 transaction. Kiểm tra "đã có" chạy ngoài transaction ghi → request khác (instance
     * khác, {@code POST /api/metrics}) ghi cùng (bài, thời điểm) xen giữa thì unique index chặn, cả lô rollback.
     */
    private SnapshotImport save(Long ownerId, List<ValidRow> rows) {
        try {
            return socialMetricService.importSnapshots(ownerId, rows.stream().map(ValidRow::request).toList());
        } catch (DataIntegrityViolationException e) {
            // Không log message gốc (chứa giá trị cột) — chỉ loại lỗi
            log.warn("User {}: metric import rolled back, a snapshot was saved concurrently ({})", ownerId,
                    e.getMostSpecificCause().getClass().getSimpleName());
            throw new DuplicateResourceException(CONCURRENT_WRITE_MESSAGE);
        }
    }

    /** Dòng của bài bị xoá giữa lúc tra và lúc ghi: không được lưu → báo "post not found" như bài không tồn tại. */
    private static void addDeletedPostErrors(List<ValidRow> rows, Set<Long> deletedPostIds,
                                             List<ExcelRowError> errors) {
        if (deletedPostIds.isEmpty()) {
            return;
        }
        for (ValidRow row : rows) {
            if (deletedPostIds.contains(row.request().postId())) {
                errors.add(new ExcelRowError(row.rowNumber(), EXTERNAL_ID_COLUMN, POST_NOT_FOUND));
            }
        }
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

    /**
     * Dòng đã qua mọi kiểm tra trong file, chờ đối chiếu với snapshot đã có trong DB.
     *
     * @param possibleCollectedAt mọi thời điểm có thể của ô Collected At ({@link #possibleInstants}) — phần tử đầu là
     *                            {@code request.collectedAt()}
     */
    private record ValidRow(int rowNumber, SocialMetricRequest request, List<Instant> possibleCollectedAt) {
    }
}

package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.config.properties.AppProperties;
import com.huuhv.socialanalytics.dto.request.PostImportRow;
import com.huuhv.socialanalytics.dto.request.PostRequest;
import com.huuhv.socialanalytics.dto.response.ImportResultResponse;
import com.huuhv.socialanalytics.entity.User;
import com.huuhv.socialanalytics.excel.ExcelColumnMetadata;
import com.huuhv.socialanalytics.excel.ExcelColumnMetadata.FieldMeta;
import com.huuhv.socialanalytics.excel.ExcelFileException;
import com.huuhv.socialanalytics.excel.ExcelReadResult;
import com.huuhv.socialanalytics.excel.ExcelRow;
import com.huuhv.socialanalytics.excel.ExcelRowError;
import com.huuhv.socialanalytics.excel.GenericExcelImporter;
import com.huuhv.socialanalytics.exception.BadRequestException;
import com.huuhv.socialanalytics.util.SocialUrlPolicy;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Import bài viết từ file Excel cho user đang đăng nhập. Dòng lỗi được báo kèm số dòng, dòng hợp lệ vẫn được lưu;
 * bài trùng (provider, externalId) với bài đã có của user thì cập nhật (upsert).
 * <p>
 * Không có transaction bao cả method: parse + validate file chạy ngoài transaction, chỉ bước ghi
 * ({@link PostUpsertService#upsert}) giữ connection DB.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostImportService {

    private static final String XLSX_EXTENSION = ".xlsx";
    // File mẫu (dữ liệu giả) — PostImportServiceTest đảm bảo file luôn import được, không lệch với PostImportRow
    private static final Resource TEMPLATE = new ClassPathResource("excel/posts-import-template.xlsx");
    private static final List<FieldMeta> COLUMNS = ExcelColumnMetadata.of(PostImportRow.class);
    private static final Map<String, String> COLUMN_BY_FIELD = COLUMNS.stream()
            .collect(Collectors.toMap(meta -> meta.field().getName(), FieldMeta::name));
    private static final Map<String, Integer> ORDER_BY_COLUMN = COLUMNS.stream()
            .collect(Collectors.toMap(FieldMeta::name, FieldMeta::order));
    // Lỗi không gắn được với cột nào (vd constraint cấp class) có column = null → xếp cuối
    private static final Comparator<ExcelRowError> BY_COLUMN_ORDER = Comparator.comparing(
            (ExcelRowError error) -> error.column() == null ? null : ORDER_BY_COLUMN.get(error.column()),
            Comparator.nullsLast(Comparator.naturalOrder()));

    private final GenericExcelImporter excelImporter;
    private final PostUpsertService postUpsertService;
    private final CurrentUserService currentUserService;
    private final Validator validator;
    private final AppProperties appProperties;
    private final Clock clock;

    /**
     * @throws BadRequestException khi file rỗng, không phải .xlsx (đuôi hoặc nội dung), vượt dung lượng giải nén,
     *                             thiếu cột hoặc quá số dòng
     */
    public ImportResultResponse importPosts(MultipartFile file) {
        // Fail closed: kiểm tra owner trước khi parse (POI). Request ẩn danh đã bị SecurityConfig chặn từ filter chain,
        // trước khi multipart được đọc — lệnh này là lớp bảo vệ thứ hai.
        User owner = currentUserService.getCurrentUser();
        ExcelReadResult<PostImportRow> result = read(file);

        List<ExcelRowError> errors = new ArrayList<>();
        List<PostRequest> validRows = validate(result.rows(), errors);
        PostUpsertService.Result counts = postUpsertService.upsert(owner, validRows, result.totalRows());

        int created = counts.created();
        int updated = counts.updated();
        int failed = result.totalRows() - created - updated;
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

    private ExcelReadResult<PostImportRow> read(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("File is empty");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(XLSX_EXTENSION)) {
            throw new BadRequestException("Only .xlsx files are accepted");
        }
        AppProperties.ImportSettings settings = appProperties.importSettings();
        try (InputStream in = FileMagic.prepareToCheckMagic(file.getInputStream())) {
            // Kiểm tra nội dung thật (magic bytes OOXML), không tin đuôi file
            if (FileMagic.valueOf(in) != FileMagic.OOXML) {
                throw new BadRequestException("Only .xlsx files are accepted");
            }
            return excelImporter.read(in, PostImportRow.class, settings.maxRows(),
                    settings.maxUncompressedSize().toBytes());
        } catch (ExcelFileException e) {
            log.warn("Rejected import file: {}", e.getMessage());
            throw new BadRequestException(e.getMessage());
        } catch (IOException e) {
            log.warn("Cannot read import file", e);
            throw new BadRequestException("Invalid Excel file");
        }
    }

    /**
     * Lỗi chuyển kiểu + Bean Validation của {@link PostRequest} (cùng quy tắc với REST API) + whitelist URL
     * (chống SSRF) + trùng trong cùng file. Mọi lỗi của 1 dòng được báo cùng lúc; dòng lỗi bị loại.
     *
     * @param errors nhận lỗi theo thứ tự dòng, trong 1 dòng theo thứ tự cột
     */
    private List<PostRequest> validate(List<ExcelRow<PostImportRow>> rows, List<ExcelRowError> errors) {
        String providerColumn = COLUMN_BY_FIELD.get("provider");
        String urlColumn = COLUMN_BY_FIELD.get("url");
        Map<String, Integer> firstRowByKey = new HashMap<>();
        List<PostRequest> valid = new ArrayList<>();
        for (ExcelRow<PostImportRow> row : rows) {
            // Ô ngày giờ Excel không có múi giờ → hiểu theo múi giờ của server (như file export)
            PostRequest request = row.value().toPostRequest(clock.getZone());
            List<ExcelRowError> rowErrors = new ArrayList<>(row.errors());
            // Cột đã lỗi chuyển kiểu có field = null → bỏ vi phạm @NotNull/@NotBlank trùng lặp của cột đó
            Set<String> unreadableColumns = row.errors().stream().map(ExcelRowError::column).collect(Collectors.toSet());
            for (ConstraintViolation<PostRequest> violation : validator.validate(request)) {
                String column = COLUMN_BY_FIELD.get(violation.getPropertyPath().toString());
                if (column == null || !unreadableColumns.contains(column)) {
                    rowErrors.add(new ExcelRowError(row.rowNumber(), column, violation.getMessage()));
                }
            }
            boolean providerAndUrlOk = rowErrors.stream()
                    .noneMatch(error -> providerColumn.equals(error.column()) || urlColumn.equals(error.column()));
            if (providerAndUrlOk && !SocialUrlPolicy.isAllowed(request.provider(), request.url())) {
                rowErrors.add(new ExcelRowError(row.rowNumber(), urlColumn, SocialUrlPolicy.VIOLATION_MESSAGE));
            }
            if (rowErrors.isEmpty()) {
                String key = PostUpsertService.matchKey(request.provider(), request.externalId());
                Integer firstRow = firstRowByKey.putIfAbsent(key, row.rowNumber());
                if (firstRow != null) {
                    rowErrors.add(new ExcelRowError(row.rowNumber(), COLUMN_BY_FIELD.get("externalId"),
                            "duplicate of row " + firstRow));
                }
            }
            if (rowErrors.isEmpty()) {
                valid.add(request);
            } else {
                // Thứ tự vi phạm của Validator không cố định → sắp theo thứ tự cột
                rowErrors.sort(BY_COLUMN_ORDER);
                errors.addAll(rowErrors);
            }
        }
        return valid;
    }
}

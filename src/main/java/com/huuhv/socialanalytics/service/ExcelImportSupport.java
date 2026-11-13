package com.huuhv.socialanalytics.service;

import com.huuhv.socialanalytics.config.properties.AppProperties;
import com.huuhv.socialanalytics.excel.ExcelColumnMetadata;
import com.huuhv.socialanalytics.excel.ExcelColumnMetadata.ColumnMeta;
import com.huuhv.socialanalytics.excel.ExcelFileException;
import com.huuhv.socialanalytics.excel.ExcelReadResult;
import com.huuhv.socialanalytics.excel.ExcelRow;
import com.huuhv.socialanalytics.excel.ExcelRowError;
import com.huuhv.socialanalytics.excel.GenericExcelImporter;
import com.huuhv.socialanalytics.exception.BadRequestException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Phần dùng chung của mọi luồng import Excel (bài viết, snapshot metric):
 * <ul>
 *     <li>{@link #read}: kiểm tra file upload (OWASP A06 — đuôi {@code .xlsx} <b>và</b> magic bytes OOXML, giới hạn
 *     {@code app.import.*}) rồi đọc bằng {@link GenericExcelImporter}; lỗi cả file → {@link BadRequestException}.</li>
 *     <li>{@link #validate}: Bean Validation của DTO request (cùng bộ quy tắc với REST API) → lỗi theo dòng/cột Excel;
 *     cột đã lỗi chuyển kiểu không báo thêm vi phạm {@code @NotNull} trùng lặp. Caller sắp lỗi của dòng bằng
 *     {@link #byColumnOrder} sau khi thêm lỗi nghiệp vụ riêng.</li>
 * </ul>
 * Tên field DTO import phải trùng tên component của DTO request để map vi phạm về đúng cột.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExcelImportSupport {

    private static final String XLSX_EXTENSION = ".xlsx";
    /** Comparator thứ tự cột theo class DTO import (metadata cột đã cache ở {@link ExcelColumnMetadata}). */
    private static final Map<Class<?>, Comparator<ExcelRowError>> COMPARATORS = new ConcurrentHashMap<>();

    private final GenericExcelImporter excelImporter;
    private final Validator validator;
    private final AppProperties appProperties;

    /**
     * @throws BadRequestException khi file rỗng, không phải .xlsx (đuôi hoặc nội dung), vượt dung lượng giải nén,
     *                             thiếu cột hoặc quá số dòng
     */
    public <T> ExcelReadResult<T> read(MultipartFile file, Class<T> rowType) {
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
            return excelImporter.read(in, rowType, settings.maxRows(), settings.maxUncompressedSize().toBytes());
        } catch (ExcelFileException e) {
            log.warn("Rejected import file: {}", e.getMessage());
            throw new BadRequestException(e.getMessage());
        } catch (IOException e) {
            log.warn("Cannot read import file", e);
            throw new BadRequestException("Invalid Excel file");
        }
    }

    /**
     * Lỗi chuyển kiểu của dòng + vi phạm Bean Validation của {@code request} (dựng từ dòng đó). Danh sách
     * <b>chưa sắp xếp</b> và sửa được: caller thêm lỗi nghiệp vụ riêng rồi sắp 1 lần bằng {@link #byColumnOrder}
     * (chỉ khi dòng có lỗi — dòng hợp lệ không tốn công sắp).
     *
     * @param row     dòng đã đọc (DTO import + lỗi chuyển kiểu)
     * @param request DTO request tương ứng (vd {@code PostRequest}) — component trùng tên field của DTO import
     */
    public List<ExcelRowError> validate(ExcelRow<?> row, Object request) {
        return validate(row, request, Set.of());
    }

    /**
     * Như {@link #validate(ExcelRow, Object)} nhưng bỏ qua vi phạm của các thuộc tính trong {@code ignoredProperties}
     * — thuộc tính không đến từ file mà do Service điền (vd {@code postId} tra từ Provider + External ID, lỗi "không
     * tìm thấy" do Service tự báo ở đúng cột).
     */
    public List<ExcelRowError> validate(ExcelRow<?> row, Object request, Set<String> ignoredProperties) {
        Class<?> rowType = row.value().getClass();
        List<ExcelRowError> errors = new ArrayList<>(row.errors());
        // Cột đã lỗi chuyển kiểu có field = null → bỏ vi phạm @NotNull/@NotBlank trùng lặp của cột đó
        Set<String> unreadableColumns = row.errors().isEmpty() ? Set.of()
                : row.errors().stream().map(ExcelRowError::column).collect(Collectors.toSet());
        for (ConstraintViolation<Object> violation : validator.validate(request)) {
            String property = violation.getPropertyPath().toString();
            if (ignoredProperties.contains(property)) {
                continue;
            }
            String column = ExcelColumnMetadata.columnName(rowType, property).orElse(null);
            if (column == null || !unreadableColumns.contains(column)) {
                errors.add(new ExcelRowError(row.rowNumber(), column, violation.getMessage()));
            }
        }
        return errors;
    }

    /**
     * Lỗi theo thứ tự cột của {@code rowType} (thứ tự vi phạm của Validator không cố định); lỗi không gắn được với cột
     * nào ({@code column = null}) xếp cuối. Comparator dựng 1 lần cho mỗi class rồi dùng lại.
     */
    public static Comparator<ExcelRowError> byColumnOrder(Class<?> rowType) {
        return COMPARATORS.computeIfAbsent(rowType, ExcelImportSupport::columnOrderComparator);
    }

    private static Comparator<ExcelRowError> columnOrderComparator(Class<?> rowType) {
        Map<String, Integer> orderByColumn = ExcelColumnMetadata.of(rowType).stream()
                .collect(Collectors.toUnmodifiableMap(ColumnMeta::name, ColumnMeta::order));
        return Comparator.comparing((ExcelRowError error) -> error.column() == null
                        ? null : orderByColumn.get(error.column()),
                Comparator.nullsLast(Comparator.naturalOrder()));
    }

    /**
     * Tên cột của field {@code property} trong DTO import.
     *
     * @throws IllegalArgumentException khi field không phải cột (lỗi lập trình)
     */
    public static String column(Class<?> rowType, String property) {
        return ExcelColumnMetadata.columnName(rowType, property)
                .orElseThrow(() -> new IllegalArgumentException("No column for " + rowType.getName() + "." + property));
    }
}

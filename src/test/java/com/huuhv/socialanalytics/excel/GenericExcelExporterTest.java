package com.huuhv.socialanalytics.excel;

import com.huuhv.socialanalytics.dto.request.PostImportRow;
import com.huuhv.socialanalytics.dto.response.PostReportRow;
import com.huuhv.socialanalytics.enums.Provider;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class GenericExcelExporterTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 1, 2, 10, 30, 15);
    private static final long MAX_BYTES = 10L * 1024 * 1024;

    private final GenericExcelExporter exporter = new GenericExcelExporter();
    private final GenericExcelImporter importer = new GenericExcelImporter();

    /** Khai báo lệch thứ tự để kiểm tra cột được sắp theo {@code order}, không theo thứ tự field. */
    record Sample(
            @ExcelColumn(name = "At", order = 5) LocalDateTime at,
            @ExcelColumn(name = "Name", order = 1) String name,
            @ExcelColumn(name = "Count", order = 2) Long count,
            @ExcelColumn(name = "Rank", order = 3) int rank,
            @ExcelColumn(name = "Day", order = 4) LocalDate day,
            @ExcelColumn(name = "Provider", order = 6) Provider provider,
            @ExcelColumn(name = "Active", order = 7) Boolean active) {
    }

    /** DTO import cùng tên cột với {@link Sample} (trừ Active) để kiểm tra round-trip. */
    @Getter
    @NoArgsConstructor
    static class ImportedSample {
        @ExcelColumn(name = "Name", order = 1)
        private String name;
        @ExcelColumn(name = "Count", order = 2)
        private Long count;
        @ExcelColumn(name = "Rank", order = 3)
        private Integer rank;
        @ExcelColumn(name = "Day", order = 4)
        private LocalDate day;
        @ExcelColumn(name = "At", order = 5)
        private LocalDateTime at;
        @ExcelColumn(name = "Provider", order = 6)
        private Provider provider;
    }

    record Unsupported(@ExcelColumn(name = "Values", order = 1) List<String> values) {
    }

    record Amount(@ExcelColumn(name = "Amount", order = 1) BigDecimal amount) {
    }

    @Test
    void writesHeaderInColumnOrderAndTypedCells() throws IOException {
        byte[] file = export(List.of(new Sample(AT, "Alice", 12L, 3, LocalDate.of(2026, 1, 1), Provider.TWITTER, true)),
                Sample.class);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            Sheet sheet = workbook.getSheet("Data");
            assertThat(texts(sheet.getRow(0))).containsExactly("Name", "Count", "Rank", "Day", "At", "Provider", "Active");
            Row row = sheet.getRow(1);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo("Alice");
            assertThat(row.getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(row.getCell(1).getNumericCellValue()).isEqualTo(12);
            assertThat(row.getCell(2).getNumericCellValue()).isEqualTo(3);
            assertThat(DateUtil.isCellDateFormatted(row.getCell(3))).isTrue();
            assertThat(row.getCell(3).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.of(2026, 1, 1));
            assertThat(DateUtil.isCellDateFormatted(row.getCell(4))).isTrue();
            assertThat(row.getCell(4).getLocalDateTimeCellValue()).isEqualTo(AT);
            assertThat(row.getCell(5).getStringCellValue()).isEqualTo("TWITTER");
            assertThat(row.getCell(6).getBooleanCellValue()).isTrue();
        }
    }

    @Test
    void leavesNullValuesBlankAndWritesOnlyHeaderForEmptyList() throws IOException {
        byte[] withNulls = export(List.of(new Sample(null, "Alice", null, 0, null, null, null)), Sample.class);
        byte[] empty = export(List.of(), Sample.class);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(withNulls))) {
            Row row = workbook.getSheetAt(0).getRow(1);
            assertThat(row.getCell(1)).isNull();
            assertThat(row.getCell(4)).isNull();
            assertThat(row.getCell(5)).isNull();
        }
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(empty))) {
            assertThat(workbook.getSheetAt(0).getLastRowNum()).isZero();
        }
    }

    @Test
    void neutralizesFormulaLikeTextWithoutChangingValue() throws IOException {
        List<String> names = List.of("=HYPERLINK(\"https://evil.com\",\"x\")", "+1", "-abc", "@SUM(A1)", "\tcmd", "plain");
        byte[] file = export(names.stream()
                .map(name -> new Sample(null, name, null, 0, null, null, null)).toList(), Sample.class);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            Sheet sheet = workbook.getSheetAt(0);
            for (int i = 0; i < names.size(); i++) {
                Cell cell = sheet.getRow(i + 1).getCell(0);
                // Luôn là ô chuỗi, không bao giờ là công thức; giá trị giữ nguyên
                assertThat(cell.getCellType()).isEqualTo(CellType.STRING);
                assertThat(cell.getStringCellValue()).isEqualTo(names.get(i));
                assertThat(cell.getCellStyle().getQuotePrefixed())
                        .as("quote prefix for %s", names.get(i))
                        .isEqualTo(!names.get(i).equals("plain"));
            }
        }
    }

    @Test
    void roundTripExportThenImportKeepsValues() throws IOException {
        List<Sample> rows = List.of(
                new Sample(AT, "=1+1", 12L, 3, LocalDate.of(2026, 1, 1), Provider.FACEBOOK, true),
                new Sample(null, "Bob", null, -7, null, Provider.TWITTER, null));

        ExcelReadResult<ImportedSample> result = importer.read(
                new ByteArrayInputStream(export(rows, Sample.class)), ImportedSample.class, 100, MAX_BYTES);

        assertThat(result.errors()).isEmpty();
        assertThat(result.rows()).extracting(ExcelRow::value)
                .extracting(ImportedSample::getName, ImportedSample::getCount, ImportedSample::getRank,
                        ImportedSample::getDay, ImportedSample::getAt, ImportedSample::getProvider)
                .containsExactly(
                        tuple("=1+1", 12L, 3, LocalDate.of(2026, 1, 1), AT, Provider.FACEBOOK),
                        tuple("Bob", null, -7, null, null, Provider.TWITTER));
    }

    @Test
    void exportedReportCanBeImportedBackAsPosts() throws IOException {
        List<PostReportRow> report = List.of(
                new PostReportRow(Provider.FACEBOOK, "-fb-1", "https://facebook.com/posts/1", "=cmd|' /C calc'!A0",
                        AT, 10L, 2L, 3L, 1000L, AT.plusHours(1)),
                new PostReportRow(Provider.TWITTER, "tw-1", "https://x.com/a/status/1", null, null,
                        null, null, null, null, null));

        ExcelReadResult<PostImportRow> result = importer.read(
                new ByteArrayInputStream(export(report, PostReportRow.class)), PostImportRow.class, 100, MAX_BYTES);

        assertThat(result.errors()).isEmpty();
        // Cùng 1 múi giờ cho export và import → round-trip giữ nguyên thời điểm
        assertThat(result.rows()).extracting(row -> row.value().toPostRequest(ZoneOffset.UTC))
                .extracting("provider", "externalId", "url", "content", "publishedAt")
                .containsExactly(
                        tuple(Provider.FACEBOOK, "-fb-1", "https://facebook.com/posts/1", "=cmd|' /C calc'!A0",
                                AT.toInstant(ZoneOffset.UTC)),
                        tuple(Provider.TWITTER, "tw-1", "https://x.com/a/status/1", null, null));
    }

    @Test
    void rejectsUnsupportedFieldType() {
        assertThatThrownBy(() -> export(List.of(new Unsupported(List.of("a"))), Unsupported.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported @ExcelColumn type");
    }

    @Test
    void rejectsBigDecimalBecauseExcelNumbersAreDoubles() {
        assertThatThrownBy(() -> export(List.of(new Amount(new BigDecimal("12345678901234567.89"))), Amount.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("java.math.BigDecimal");
    }

    @Test
    void writesLongBeyondDoublePrecisionAsTextAndRoundTrips() throws IOException {
        long exactLimit = 1L << 53;
        List<Long> counts = List.of(exactLimit, exactLimit + 1, -(exactLimit + 1), Long.MAX_VALUE);
        byte[] file = export(counts.stream()
                .map(count -> new Sample(null, "n", count, 0, null, null, null)).toList(), Sample.class);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            Sheet sheet = workbook.getSheetAt(0);
            // 2^53 còn biểu diễn chính xác → ô số; vượt ngưỡng → ô text giữ đủ chữ số
            assertThat(sheet.getRow(1).getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(sheet.getRow(2).getCell(1).getStringCellValue()).isEqualTo("9007199254740993");
            assertThat(sheet.getRow(3).getCell(1).getStringCellValue()).isEqualTo("-9007199254740993");
        }
        ExcelReadResult<ImportedSample> result = importer.read(
                new ByteArrayInputStream(file), ImportedSample.class, 100, MAX_BYTES);
        assertThat(result.errors()).isEmpty();
        assertThat(result.rows()).extracting(row -> row.value().getCount()).containsExactlyElementsOf(counts);
    }

    @Test
    void propagatesWriteFailureAndDeletesTempFiles() {
        long before = countSxssfTempFiles();
        long[] duringWrite = new long[1];
        OutputStream failing = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                duringWrite[0] = countSxssfTempFiles();
                throw new IOException("disk full");
            }
        };
        List<Sample> rows = List.of(new Sample(AT, "Alice", 1L, 1, null, null, null));

        // Exception gốc của write không bị close() che mất
        assertThatThrownBy(() -> exporter.write(rows, Sample.class, "Data", failing))
                .isInstanceOf(IOException.class)
                .hasMessage("disk full");
        // File tạm tồn tại trong lúc ghi và được xoá sau khi lỗi
        assertThat(duringWrite[0]).isGreaterThan(before);
        assertThat(countSxssfTempFiles()).isEqualTo(before);
    }

    /** File tạm của SXSSF nằm trong {@code java.io.tmpdir/poifiles} (DefaultTempFileCreationStrategy của POI). */
    private static long countSxssfTempFiles() {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "poifiles");
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(path -> path.getFileName().toString().startsWith("poi-sxssf-sheet")).count();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private <T> byte[] export(List<T> rows, Class<T> type) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exporter.write(rows, type, "Data", out);
        return out.toByteArray();
    }

    private static List<String> texts(Row row) {
        return IntStream.range(0, row.getLastCellNum())
                .mapToObj(i -> row.getCell(i).getStringCellValue()).toList();
    }
}

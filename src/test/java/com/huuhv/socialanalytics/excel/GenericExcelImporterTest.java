package com.huuhv.socialanalytics.excel;

import com.huuhv.socialanalytics.enums.Provider;
import com.huuhv.socialanalytics.excel.TestWorkbooks.Formula;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.huuhv.socialanalytics.excel.TestWorkbooks.xlsx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenericExcelImporterTest {

    private static final Object[] HEADER = {"Name", "Count", "Rank", "Day", "At", "Provider"};
    private static final long MAX_BYTES = 10L * 1024 * 1024;

    private final GenericExcelImporter importer = new GenericExcelImporter();

    @Getter
    @NoArgsConstructor
    static class Sample {
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

    static class NoDefaultConstructor {
        @ExcelColumn(name = "Name", order = 1)
        private final String name;

        NoDefaultConstructor(String name) {
            this.name = name;
        }
    }

    @Test
    void mapsTypedCellsByHeaderName() {
        LocalDateTime at = LocalDateTime.of(2026, 1, 2, 10, 30);
        ExcelReadResult<Sample> result = read(xlsx(HEADER,
                new Object[]{" Alice ", 12, 3, LocalDate.of(2026, 1, 1), at, "facebook"}));

        assertThat(result.errors()).isEmpty();
        assertThat(result.totalRows()).isEqualTo(1);
        ExcelRow<Sample> row = result.rows().getFirst();
        assertThat(row.rowNumber()).isEqualTo(2);
        Sample sample = row.value();
        assertThat(sample.getName()).isEqualTo("Alice");
        assertThat(sample.getCount()).isEqualTo(12L);
        assertThat(sample.getRank()).isEqualTo(3);
        assertThat(sample.getDay()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(sample.getAt()).isEqualTo(at);
        assertThat(sample.getProvider()).isEqualTo(Provider.FACEBOOK);
    }

    @Test
    void matchesHeaderIgnoringOrderCaseAndUnknownColumns() {
        ExcelReadResult<Sample> result = read(xlsx(
                new Object[]{"PROVIDER", "extra", " name ", "at", "day", "rank", "count"},
                new Object[]{"TWITTER", "ignored", "Bob", "2026-01-02 10:30:00", "2026-01-01", "7", "8"}));

        assertThat(result.errors()).isEmpty();
        Sample sample = result.rows().getFirst().value();
        assertThat(sample.getProvider()).isEqualTo(Provider.TWITTER);
        assertThat(sample.getName()).isEqualTo("Bob");
        // Ngày dạng chuỗi ISO / "yyyy-MM-dd HH:mm:ss", số dạng chuỗi
        assertThat(sample.getAt()).isEqualTo(LocalDateTime.of(2026, 1, 2, 10, 30));
        assertThat(sample.getDay()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(sample.getRank()).isEqualTo(7);
        assertThat(sample.getCount()).isEqualTo(8L);
    }

    @Test
    void numericCellIntoStringFieldHasNoDecimalSuffix() {
        ExcelReadResult<Sample> result = read(xlsx(HEADER, new Object[]{12345, null, null, null, null, null}));

        assertThat(result.rows().getFirst().value().getName()).isEqualTo("12345");
    }

    @Test
    void rejectsNumericCellWithMoreThan15DigitsIntoStringField() {
        // Excel đã làm tròn số 16+ chữ số → không được âm thầm chuyển thành ID sai
        ExcelReadResult<Sample> result = read(xlsx(HEADER,
                new Object[]{1234567890123456L},
                new Object[]{1.23456789012346E18},
                new Object[]{999_999_999_999_999L}));

        assertThat(result.errors()).containsExactly(
                new ExcelRowError(2, "Name", "numbers with more than 15 digits must be entered as text"),
                new ExcelRowError(3, "Name", "numbers with more than 15 digits must be entered as text"));
        assertThat(result.validRows()).singleElement()
                .satisfies(row -> assertThat(row.value().getName()).isEqualTo("999999999999999"));
    }

    @Test
    void rowWithConversionErrorKeepsOtherValuesForFurtherValidation() {
        ExcelReadResult<Sample> result = read(xlsx(HEADER, new Object[]{"Alice", "many", 4}));

        ExcelRow<Sample> row = result.rows().getFirst();
        assertThat(row.isValid()).isFalse();
        assertThat(row.errors()).extracting(ExcelRowError::column).containsExactly("Count");
        assertThat(row.value().getName()).isEqualTo("Alice");
        assertThat(row.value().getCount()).isNull();
        assertThat(row.value().getRank()).isEqualTo(4);
    }

    @Test
    void readsInlineStringCellsWrittenByStreamingWriter() throws Exception {
        // SXSSF ghi chuỗi dạng inlineStr thay vì shared strings
        byte[] file;
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet();
            Row header = sheet.createRow(0);
            for (int c = 0; c < HEADER.length; c++) {
                header.createCell(c).setCellValue(HEADER[c].toString());
            }
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("Inline");
            data.createCell(5).setCellValue("TWITTER");
            workbook.write(out);
            workbook.dispose();
            file = out.toByteArray();
        }

        ExcelReadResult<Sample> result = read(file);

        assertThat(result.errors()).isEmpty();
        assertThat(result.rows().getFirst().value().getName()).isEqualTo("Inline");
        assertThat(result.rows().getFirst().value().getProvider()).isEqualTo(Provider.TWITTER);
    }

    @Test
    void failsWhenUncompressedContentExceedsLimit() {
        // Nội dung lặp lại nén rất tốt: file nhỏ nhưng giải nén ra lớn
        byte[] file = xlsx(HEADER, new Object[]{"a".repeat(30_000)}, new Object[]{"b".repeat(30_000)});

        assertThatThrownBy(() -> importer.read(new ByteArrayInputStream(file), Sample.class, 100, 20_000))
                .isInstanceOf(ExcelFileException.class)
                .hasMessage("File content exceeds the maximum uncompressed size");
    }

    @Test
    void isSafeForConcurrentReads() throws Exception {
        byte[] file = xlsx(HEADER, new Object[]{"Alice", 1, 2, LocalDate.of(2026, 1, 1)});
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Future<ExcelReadResult<Sample>>> futures = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                futures.add(executor.submit(() -> read(file)));
            }
            for (Future<ExcelReadResult<Sample>> future : futures) {
                ExcelReadResult<Sample> result = future.get();
                assertThat(result.errors()).isEmpty();
                assertThat(result.rows().getFirst().value().getName()).isEqualTo("Alice");
            }
        }
    }

    @Test
    void skipsBlankRowsAndLeavesEmptyCellsNull() {
        ExcelReadResult<Sample> result = read(xlsx(HEADER,
                new Object[]{"Alice"},
                null,
                new Object[]{"  ", null, null},
                new Object[]{"Bob"}));

        assertThat(result.totalRows()).isEqualTo(2);
        assertThat(result.rows()).extracting(ExcelRow::rowNumber).containsExactly(2, 5);
        assertThat(result.rows().getFirst().value().getCount()).isNull();
    }

    @Test
    void reportsConversionErrorsPerRowAndContinues() {
        ExcelReadResult<Sample> result = read(xlsx(HEADER,
                new Object[]{"Alice", "many", 1.5, "01/01/2026", null, "MYSPACE"},
                new Object[]{"Bob", 2}));

        assertThat(result.totalRows()).isEqualTo(2);
        assertThat(result.validRows()).extracting(ExcelRow::rowNumber).containsExactly(3);
        assertThat(result.errors()).containsExactly(
                new ExcelRowError(2, "Count", "must be a whole number"),
                new ExcelRowError(2, "Rank", "must be a whole number"),
                new ExcelRowError(2, "Day", "must be a date (yyyy-MM-dd)"),
                new ExcelRowError(2, "Provider", "must be one of FACEBOOK, TWITTER"));
    }

    @Test
    void errorMessagesDoNotEchoCellValue() {
        ExcelReadResult<Sample> result = read(xlsx(HEADER, new Object[]{"Alice", "<script>alert(1)</script>"}));

        assertThat(result.errors()).singleElement()
                .extracting(ExcelRowError::message).asString().doesNotContain("script");
    }

    @Test
    void rejectsFormulaCells() {
        ExcelReadResult<Sample> result = read(xlsx(HEADER,
                new Object[]{new Formula("HYPERLINK(\"https://evil.com\")"), 1}));

        assertThat(result.validRows()).isEmpty();
        assertThat(result.errors()).containsExactly(new ExcelRowError(2, "Name", "formulas are not allowed"));
    }

    @Test
    void failsWhenColumnIsMissing() {
        assertThatThrownBy(() -> read(xlsx(new Object[]{"Name", "Count", "Rank"}, new Object[]{"Alice"})))
                .isInstanceOf(ExcelFileException.class)
                .hasMessage("Missing columns: Day, At, Provider");
    }

    @Test
    void failsWhenHeaderRowIsMissing() {
        assertThatThrownBy(() -> read(xlsx(null, new Object[]{"Alice"})))
                .isInstanceOf(ExcelFileException.class)
                .hasMessage("Missing header row");
    }

    @Test
    void failsWhenRowsExceedLimit() {
        byte[] file = xlsx(HEADER, new Object[]{"A"}, new Object[]{"B"}, new Object[]{"C"});

        assertThat(importer.read(new ByteArrayInputStream(file), Sample.class, 3, MAX_BYTES).totalRows()).isEqualTo(3);
        assertThatThrownBy(() -> importer.read(new ByteArrayInputStream(file), Sample.class, 2, MAX_BYTES))
                .isInstanceOf(ExcelFileException.class)
                .hasMessage("File exceeds the maximum of 2 data rows");
    }

    @Test
    void failsOnNonExcelContent() {
        assertThatThrownBy(() -> importer.read(new ByteArrayInputStream("a,b\n1,2".getBytes()), Sample.class, 10, MAX_BYTES))
                .isInstanceOf(ExcelFileException.class)
                .hasMessage("Invalid Excel file");
    }

    @Test
    void requiresNoArgConstructor() {
        assertThatThrownBy(() -> importer.read(new ByteArrayInputStream(xlsx(new Object[]{"Name"})),
                NoDefaultConstructor.class, 10, MAX_BYTES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no-arg constructor");
    }

    @Test
    void cachesColumnMetadataPerClass() {
        assertThat(ExcelColumnMetadata.of(Sample.class))
                .isSameAs(ExcelColumnMetadata.of(Sample.class))
                .extracting(ExcelColumnMetadata.FieldMeta::name)
                .containsExactly("Name", "Count", "Rank", "Day", "At", "Provider");
    }

    private ExcelReadResult<Sample> read(byte[] file) {
        return importer.read(new ByteArrayInputStream(file), Sample.class, 100, MAX_BYTES);
    }
}

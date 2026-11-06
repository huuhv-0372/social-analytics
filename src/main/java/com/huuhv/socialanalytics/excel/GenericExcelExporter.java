package com.huuhv.socialanalytics.excel;

import com.huuhv.socialanalytics.excel.ExcelColumnMetadata.FieldMeta;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Ghi danh sách DTO ra 1 sheet .xlsx: dòng 1 là header theo {@link ExcelColumn#name()}, cột theo {@link ExcelColumn#order()}.
 * File ghi ra đọc lại được bằng {@link GenericExcelImporter} (round-trip) khi DTO import có cùng tên cột.
 * <p>
 * Dùng {@link SXSSFWorkbook} (chỉ giữ cửa sổ dòng trong bộ nhớ, phần còn lại ghi ra file tạm) và luôn
 * {@code dispose()} file tạm sau khi ghi (kể cả khi ghi lỗi). Kiểu field hỗ trợ: String, Long/Integer/Short/Byte/
 * Double/Float (wrapper hoặc primitive), Boolean, LocalDate, LocalDateTime, Enum. {@code BigDecimal}/{@code BigInteger}
 * bị từ chối vì ô số của Excel là double. Long có trị tuyệt đối vượt 2^53 (double không biểu diễn chính xác) được ghi
 * dạng text để không mất chữ số — importer vẫn đọc lại được vào field Long.
 * Class không giữ trạng thái → dùng chung an toàn giữa các luồng.
 * <p>
 * Chống Formula Injection (OWASP): ô luôn ghi dạng giá trị (không bao giờ là công thức); chuỗi bắt đầu bằng
 * {@code = + - @}, tab hoặc CR được đánh dấu "quote prefix" — giống gõ {@code '} trước nội dung trong Excel — nên Excel
 * hiển thị nguyên văn, không diễn giải thành công thức, mà giá trị ô vẫn giữ nguyên để import lại không bị sai lệch.
 */
@Component
public class GenericExcelExporter {

    public static final String XLSX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** Số dòng SXSSF giữ trong bộ nhớ, các dòng cũ hơn được ghi ra file tạm. */
    private static final int ROW_ACCESS_WINDOW = 100;
    private static final String FORMULA_TRIGGERS = "=+-@\t\r";
    private static final int MIN_COLUMN_WIDTH = 12;
    private static final int TEXT_COLUMN_WIDTH = 40;
    /** Số nguyên lớn nhất double biểu diễn chính xác (2^53); Long vượt ngưỡng này ghi dạng text. */
    private static final long MAX_EXACT_DOUBLE_INTEGER = 1L << 53;
    private static final Set<Class<?>> NUMERIC_TYPES = Set.of(
            Long.class, Integer.class, Short.class, Byte.class, Double.class, Float.class,
            long.class, int.class, short.class, byte.class, double.class, float.class);

    /**
     * @param sheetName tên sheet (do code đặt, không lấy từ input người dùng)
     * @throws IllegalArgumentException khi DTO có field kiểu không hỗ trợ (lỗi lập trình)
     */
    public <T> void write(List<T> rows, Class<T> type, String sheetName, OutputStream out) throws IOException {
        List<FieldMeta> fields = ExcelColumnMetadata.of(type);
        checkSupportedTypes(type, fields);
        SXSSFWorkbook workbook = new SXSSFWorkbook(ROW_ACCESS_WINDOW);
        // try-with-resources: close() lỗi không che exception gốc (addSuppressed);
        // finally chạy sau close() nên file tạm luôn được xoá kể cả khi write/close ném lỗi
        try (workbook) {
            workbook.setCompressTempFiles(true);
            Styles styles = Styles.create(workbook);
            Sheet sheet = workbook.createSheet(sheetName);
            writeHeader(sheet, fields, styles);
            int rowIndex = 1;
            for (T value : rows) {
                writeRow(sheet.createRow(rowIndex++), value, fields, styles);
            }
            workbook.write(out);
        } finally {
            workbook.dispose(); // xoá file tạm của SXSSF
        }
    }

    private static void checkSupportedTypes(Class<?> type, List<FieldMeta> fields) {
        for (FieldMeta meta : fields) {
            Class<?> fieldType = meta.type();
            boolean supported = fieldType == String.class || fieldType == Boolean.class || fieldType == boolean.class
                    || NUMERIC_TYPES.contains(fieldType)
                    || fieldType == LocalDate.class || fieldType == LocalDateTime.class || fieldType.isEnum();
            if (!supported) {
                throw new IllegalArgumentException("Unsupported @ExcelColumn type " + fieldType.getName()
                        + " on " + type.getName() + "." + meta.field().getName());
            }
        }
    }

    private static void writeHeader(Sheet sheet, List<FieldMeta> fields, Styles styles) {
        Row header = sheet.createRow(0);
        for (int c = 0; c < fields.size(); c++) {
            FieldMeta meta = fields.get(c);
            Cell cell = header.createCell(c);
            cell.setCellValue(meta.name());
            cell.setCellStyle(styles.header());
            int width = meta.type() == String.class ? TEXT_COLUMN_WIDTH : Math.max(MIN_COLUMN_WIDTH, meta.name().length() + 2);
            if (meta.type() == LocalDateTime.class) {
                width = Math.max(width, 20);
            }
            sheet.setColumnWidth(c, width * 256);
        }
        sheet.createFreezePane(0, 1);
    }

    private static <T> void writeRow(Row row, T value, List<FieldMeta> fields, Styles styles) {
        for (int c = 0; c < fields.size(); c++) {
            Object cellValue = read(fields.get(c), value);
            if (cellValue == null) {
                continue;
            }
            Cell cell = row.createCell(c);
            switch (cellValue) {
                case String text -> writeText(cell, text, styles);
                case Long number when number > MAX_EXACT_DOUBLE_INTEGER || number < -MAX_EXACT_DOUBLE_INTEGER ->
                        writeText(cell, number.toString(), styles);
                case Number number -> cell.setCellValue(number.doubleValue());
                case Boolean bool -> cell.setCellValue(bool);
                case LocalDateTime dateTime -> {
                    cell.setCellValue(dateTime);
                    cell.setCellStyle(styles.dateTime());
                }
                case LocalDate date -> {
                    cell.setCellValue(date);
                    cell.setCellStyle(styles.date());
                }
                case Enum<?> constant -> cell.setCellValue(constant.name());
                default -> throw new IllegalStateException("Unsupported value type " + cellValue.getClass().getName());
            }
        }
    }

    private static void writeText(Cell cell, String text, Styles styles) {
        cell.setCellValue(text);
        if (!text.isEmpty() && FORMULA_TRIGGERS.indexOf(text.charAt(0)) >= 0) {
            cell.setCellStyle(styles.quoted());
        }
    }

    private static Object read(FieldMeta meta, Object target) {
        try {
            return meta.field().get(target);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read field " + meta.field().getName(), e);
        }
    }

    /** Style tạo 1 lần cho mỗi workbook (Excel giới hạn số style, không tạo theo từng ô). */
    private record Styles(CellStyle header, CellStyle date, CellStyle dateTime, CellStyle quoted) {

        static Styles create(SXSSFWorkbook workbook) {
            Font bold = workbook.createFont();
            bold.setBold(true);
            CellStyle header = workbook.createCellStyle();
            header.setFont(bold);
            short dateFormat = workbook.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd");
            short dateTimeFormat = workbook.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss");
            CellStyle date = workbook.createCellStyle();
            date.setDataFormat(dateFormat);
            CellStyle dateTime = workbook.createCellStyle();
            dateTime.setDataFormat(dateTimeFormat);
            CellStyle quoted = workbook.createCellStyle();
            quoted.setQuotePrefixed(true);
            return new Styles(header, date, dateTime, quoted);
        }
    }
}

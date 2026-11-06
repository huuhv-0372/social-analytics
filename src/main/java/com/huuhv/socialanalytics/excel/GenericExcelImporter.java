package com.huuhv.socialanalytics.excel;

import com.huuhv.socialanalytics.excel.ExcelColumnMetadata.FieldMeta;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.poi.ooxml.POIXMLTypeLoader;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.util.NumberToTextConverter;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.xmlbeans.XmlException;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTWorkbook;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.WorkbookDocument;
import org.springframework.stereotype.Component;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Đọc sheet đầu tiên của file .xlsx thành danh sách DTO: dòng 1 là header, cột map vào field có {@link ExcelColumn}
 * theo tên. Lỗi chuyển kiểu được ghi theo dòng thay vì làm hỏng cả file; validate nghiệp vụ là việc của Service.
 * <p>
 * DTO phải có constructor không tham số. Kiểu field hỗ trợ: String, Long, Integer, LocalDate, LocalDateTime, Enum.
 * <p>
 * Giới hạn tài nguyên (OWASP A06): tổng dung lượng giải nén bị chặn trước khi POI mở file (cùng cấu hình chống
 * zip bomb mặc định của {@code ZipSecureFile}); sheet được đọc streaming (SAX) và dừng ngay khi vượt {@code maxRows},
 * không dựng cả workbook trong bộ nhớ. Class không giữ trạng thái → dùng chung an toàn giữa các luồng.
 */
@Component
public class GenericExcelImporter {

    private static final Set<Class<?>> SUPPORTED_TYPES =
            Set.of(String.class, Long.class, Integer.class, LocalDate.class, LocalDateTime.class);
    /** Excel chỉ giữ 15 chữ số có nghĩa — số từ 10^15 trở lên có thể đã bị làm tròn khi nhập. */
    private static final double MAX_EXACT_EXCEL_NUMBER = 1e15;

    /**
     * @param in                   nội dung file; caller chịu trách nhiệm giới hạn dung lượng nén (multipart)
     * @param maxRows              số dòng dữ liệu tối đa (không tính header và dòng trống)
     * @param maxUncompressedBytes tổng dung lượng tối đa sau giải nén của mọi entry trong file
     * @throws ExcelFileException khi không đọc được file, vượt giới hạn dung lượng, thiếu cột hoặc vượt {@code maxRows}
     */
    public <T> ExcelReadResult<T> read(InputStream in, Class<T> type, int maxRows, long maxUncompressedBytes) {
        List<FieldMeta> fields = ExcelColumnMetadata.of(type);
        Constructor<T> constructor = noArgConstructor(type, fields);
        byte[] content = readAll(in);
        checkUncompressedSize(content, maxUncompressedBytes);

        WorkbookSource source = open(content);
        try (InputStream sheet = source.sheet()) {
            SheetHandler<T> handler = new SheetHandler<>(source, fields, constructor, maxRows);
            XMLReader xmlReader = XMLHelper.newXMLReader(); // parser an toàn của POI: tắt DTD / external entity
            xmlReader.setContentHandler(handler);
            xmlReader.parse(new InputSource(sheet));
            return handler.result();
        } catch (IOException | SAXException | ParserConfigurationException | NumberFormatException e) {
            // NumberFormatException: thuộc tính r / s của row, cell không phải số → file hỏng
            throw new ExcelFileException("Invalid Excel file", e);
        } finally {
            source.pkg().revert(); // đóng package, không ghi lại
        }
    }

    private static byte[] readAll(InputStream in) {
        try {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new ExcelFileException("Invalid Excel file", e);
        }
    }

    /**
     * Đếm số byte giải nén thực tế (không tin kích thước khai báo trong header zip), dừng ngay khi vượt giới hạn.
     * Dùng cùng thư viện zip với POI (commons-compress) để đọc được cả file ghi bằng data descriptor (vd SXSSF).
     */
    private static void checkUncompressedSize(byte[] content, long maxBytes) {
        long total = 0;
        byte[] buffer = new byte[8192];
        try (ZipArchiveInputStream zip = new ZipArchiveInputStream(
                new ByteArrayInputStream(content), StandardCharsets.UTF_8.name(), true, true)) {
            while (zip.getNextEntry() != null) {
                int read;
                while ((read = zip.read(buffer)) > 0) {
                    total += read;
                    if (total > maxBytes) {
                        throw new ExcelFileException("File content exceeds the maximum uncompressed size");
                    }
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new ExcelFileException("Invalid Excel file", e);
        }
    }

    /** Package đã mở + stream XML của sheet đầu tiên + dữ liệu dùng chung (shared strings, styles). */
    private record WorkbookSource(OPCPackage pkg, InputStream sheet, ReadOnlySharedStringsTable strings,
                                  StylesTable styles, boolean date1904) {
    }

    private static WorkbookSource open(byte[] content) {
        OPCPackage pkg = null;
        try {
            pkg = OPCPackage.open(new ByteArrayInputStream(content));
            XSSFReader reader = new XSSFReader(pkg);
            Iterator<InputStream> sheets = reader.getSheetsData();
            if (!sheets.hasNext()) {
                throw new ExcelFileException("Workbook has no sheet");
            }
            return new WorkbookSource(pkg, sheets.next(), new ReadOnlySharedStringsTable(pkg, false),
                    reader.getStylesTable(), isDate1904(reader));
        } catch (ExcelFileException e) {
            pkg.revert();
            throw e;
        } catch (IOException | OpenXML4JException | XmlException | SAXException | RuntimeException e) {
            // POI ném nhiều loại exception khi file hỏng / không phải OOXML / bị chặn zip bomb
            if (pkg != null) {
                pkg.revert();
            }
            throw new ExcelFileException("Invalid Excel file", e);
        }
    }

    private static boolean isDate1904(XSSFReader reader) throws IOException, OpenXML4JException, XmlException {
        try (InputStream in = reader.getWorkbookData()) {
            CTWorkbook workbook = WorkbookDocument.Factory.parse(in, POIXMLTypeLoader.DEFAULT_XML_OPTIONS).getWorkbook();
            return workbook.isSetWorkbookPr() && workbook.getWorkbookPr().getDate1904();
        }
    }

    private static <T> Constructor<T> noArgConstructor(Class<T> type, List<FieldMeta> fields) {
        for (FieldMeta meta : fields) {
            if (!SUPPORTED_TYPES.contains(meta.type()) && !meta.type().isEnum()) {
                throw new IllegalArgumentException("Unsupported @ExcelColumn type " + meta.type().getName()
                        + " on " + type.getName() + "." + meta.field().getName());
            }
        }
        try {
            Constructor<T> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor;
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(type.getName() + " must have a no-arg constructor", e);
        }
    }

    private enum Kind { BLANK, STRING, NUMERIC, BOOLEAN, ERROR, FORMULA }

    /**
     * Giá trị 1 ô đọc từ XML của sheet.
     *
     * @param date giá trị ngày khi ô số có định dạng ngày, ngược lại null
     */
    private record CellValue(Kind kind, String text, double number, LocalDateTime date) {

        static final CellValue BLANK = new CellValue(Kind.BLANK, "", 0, null);
        static final CellValue ERROR = new CellValue(Kind.ERROR, "", 0, null);
        static final CellValue FORMULA = new CellValue(Kind.FORMULA, "", 0, null);

        static CellValue text(String text) {
            return new CellValue(Kind.STRING, text, 0, null);
        }

        boolean isBlank() {
            return kind == Kind.BLANK || (kind == Kind.STRING && text.isBlank());
        }

        /** Nội dung ô header dạng chuỗi để so khớp với tên cột. */
        String headerText() {
            return switch (kind) {
                case STRING -> text;
                case NUMERIC -> date != null ? date.toString() : NumberToTextConverter.toText(number);
                case BOOLEAN -> "1".equals(text) ? "TRUE" : "FALSE";
                default -> "";
            };
        }
    }

    /**
     * SAX handler cho XML của 1 sheet (phần tử {@code row}/{@code c}/{@code v}/{@code f}/{@code is}). Mỗi lần
     * {@code read()} tạo 1 instance riêng → không chia sẻ trạng thái giữa các request.
     */
    private static final class SheetHandler<T> extends DefaultHandler {

        private final WorkbookSource source;
        private final List<FieldMeta> fields;
        private final Constructor<T> constructor;
        private final int maxRows;
        private final Map<Integer, Boolean> dateStyleCache = new HashMap<>();

        private final List<ExcelRow<T>> rows = new ArrayList<>();
        private Map<FieldMeta, Integer> columns; // null cho tới khi đọc xong header
        private Set<Integer> wantedColumns;
        private int dataRowCount;

        private int rowNumber;
        private int nextColumn;
        private Map<Integer, CellValue> rowCells = new HashMap<>();

        private int cellColumn;
        private String cellType;
        private int cellStyle;
        private boolean cellHasFormula;
        private boolean capturing;
        private boolean inInlineString;
        private boolean inPhonetic;
        private final StringBuilder cellText = new StringBuilder();

        SheetHandler(WorkbookSource source, List<FieldMeta> fields, Constructor<T> constructor, int maxRows) {
            this.source = source;
            this.fields = fields;
            this.constructor = constructor;
            this.maxRows = maxRows;
        }

        ExcelReadResult<T> result() {
            return new ExcelReadResult<>(List.copyOf(rows), dataRowCount);
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            switch (localName) {
                case "row" -> {
                    String ref = attributes.getValue("r");
                    rowNumber = ref != null ? Integer.parseInt(ref) : rowNumber + 1;
                    nextColumn = 0;
                    rowCells = new HashMap<>();
                }
                case "c" -> {
                    int column = columnIndex(attributes.getValue("r"));
                    cellColumn = column >= 0 ? column : nextColumn;
                    nextColumn = cellColumn + 1;
                    cellType = attributes.getValue("t");
                    String style = attributes.getValue("s");
                    cellStyle = style != null ? Integer.parseInt(style) : 0;
                    cellHasFormula = false;
                    cellText.setLength(0);
                }
                case "f" -> cellHasFormula = true;
                case "v" -> capturing = true;
                case "is" -> inInlineString = true;
                case "rPh" -> inPhonetic = true;
                case "t" -> capturing = inInlineString && !inPhonetic;
                default -> {
                    // phần tử khác (sheetPr, cols, mergeCells...) không ảnh hưởng dữ liệu
                }
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (capturing) {
                cellText.append(ch, start, length);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            switch (localName) {
                case "v", "t" -> capturing = false;
                case "is" -> inInlineString = false;
                case "rPh" -> inPhonetic = false;
                case "c" -> {
                    if (wantedColumns == null || wantedColumns.contains(cellColumn)) {
                        rowCells.put(cellColumn, buildCell());
                    }
                }
                case "row" -> endRow();
                default -> {
                    // không cần xử lý
                }
            }
        }

        @Override
        public void endDocument() {
            if (columns == null) {
                throw new ExcelFileException("Missing header row");
            }
        }

        private void endRow() {
            if (columns == null) {
                if (rowNumber != 1) {
                    throw new ExcelFileException("Missing header row");
                }
                columns = mapHeader(rowCells, fields);
                wantedColumns = Set.copyOf(columns.values());
                return;
            }
            if (isBlankRow()) {
                return;
            }
            if (++dataRowCount > maxRows) {
                // Dừng parse ngay — không đọc tiếp phần còn lại của sheet
                throw new ExcelFileException("File exceeds the maximum of " + maxRows + " data rows");
            }
            rows.add(mapRow(rowNumber, rowCells, fields, columns, constructor));
        }

        private boolean isBlankRow() {
            for (int index : wantedColumns) {
                CellValue cell = rowCells.get(index);
                if (cell != null && !cell.isBlank()) {
                    return false;
                }
            }
            return true;
        }

        private CellValue buildCell() {
            if (cellHasFormula) {
                return CellValue.FORMULA;
            }
            String text = cellText.toString();
            String type = cellType == null ? "n" : cellType;
            return switch (type) {
                case "s" -> sharedString(text);
                case "inlineStr", "str", "d" -> CellValue.text(text);
                case "b" -> new CellValue(Kind.BOOLEAN, text, 0, null);
                case "e" -> CellValue.ERROR;
                default -> number(text);
            };
        }

        private CellValue sharedString(String index) {
            try {
                return CellValue.text(source.strings().getItemAt(Integer.parseInt(index.strip())).getString());
            } catch (NumberFormatException | IndexOutOfBoundsException e) {
                return CellValue.ERROR;
            }
        }

        private CellValue number(String text) {
            if (text.isBlank()) {
                return CellValue.BLANK;
            }
            try {
                double value = Double.parseDouble(text);
                LocalDateTime date = isDateStyle(cellStyle) && DateUtil.isValidExcelDate(value)
                        ? DateUtil.getLocalDateTime(value, source.date1904())
                        : null;
                return new CellValue(Kind.NUMERIC, text, value, date);
            } catch (NumberFormatException e) {
                return CellValue.ERROR;
            }
        }

        private boolean isDateStyle(int styleIndex) {
            StylesTable styles = source.styles();
            if (styles == null) {
                return false;
            }
            return dateStyleCache.computeIfAbsent(styleIndex, index -> {
                if (index < 0 || index >= styles.getNumCellStyles()) {
                    return false;
                }
                XSSFCellStyle style = styles.getStyleAt(index);
                String pattern = style == null ? null : style.getDataFormatString();
                return pattern != null && DateUtil.isADateFormat(style.getDataFormat(), pattern);
            });
        }

        /** "AB12" → 27; trả -1 khi không có tham chiếu ô. */
        private static int columnIndex(String ref) {
            if (ref == null) {
                return -1;
            }
            int column = 0;
            for (int i = 0; i < ref.length(); i++) {
                char ch = ref.charAt(i);
                if (ch < 'A' || ch > 'Z') {
                    break;
                }
                column = column * 26 + (ch - 'A' + 1);
            }
            return column - 1;
        }
    }

    private static Map<FieldMeta, Integer> mapHeader(Map<Integer, CellValue> header, List<FieldMeta> fields) {
        Map<String, Integer> indexByName = new HashMap<>();
        header.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String name = entry.getValue().headerText().strip().toLowerCase(Locale.ROOT);
                    if (!name.isEmpty()) {
                        indexByName.putIfAbsent(name, entry.getKey());
                    }
                });
        Map<FieldMeta, Integer> columns = new HashMap<>();
        List<String> missing = new ArrayList<>();
        for (FieldMeta meta : fields) {
            Integer index = indexByName.get(meta.name().toLowerCase(Locale.ROOT));
            if (index == null) {
                missing.add(meta.name());
            } else {
                columns.put(meta, index);
            }
        }
        if (!missing.isEmpty()) {
            throw new ExcelFileException("Missing columns: " + String.join(", ", missing));
        }
        return columns;
    }

    /**
     * Ô lỗi chuyển kiểu để field tương ứng là null và ghi lỗi; dòng vẫn được trả về để Service validate tiếp
     * các cột còn lại (user thấy đủ lỗi của dòng trong 1 lần import).
     */
    private static <T> ExcelRow<T> mapRow(int rowNumber, Map<Integer, CellValue> cells, List<FieldMeta> fields,
                                          Map<FieldMeta, Integer> columns, Constructor<T> constructor) {
        T instance = newInstance(constructor);
        List<ExcelRowError> errors = new ArrayList<>();
        for (FieldMeta meta : fields) {
            try {
                Object value = convert(cells.get(columns.get(meta)), meta.type());
                if (value != null) {
                    meta.field().set(instance, value);
                }
            } catch (CellConversionException e) {
                errors.add(new ExcelRowError(rowNumber, meta.name(), e.getMessage()));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot set field " + meta.field().getName(), e);
            }
        }
        return new ExcelRow<>(rowNumber, instance, List.copyOf(errors));
    }

    private static <T> T newInstance(Constructor<T> constructor) {
        try {
            return constructor.newInstance();
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Cannot instantiate " + constructor.getDeclaringClass().getName(), e);
        }
    }

    /** Thông điệp lỗi chỉ mô tả định dạng mong đợi, không chứa giá trị ô. */
    private static Object convert(CellValue cell, Class<?> type) {
        if (cell == null || cell.isBlank()) {
            return null;
        }
        if (cell.kind() == Kind.FORMULA) {
            throw new CellConversionException("formulas are not allowed");
        }
        if (cell.kind() == Kind.ERROR) {
            throw new CellConversionException("invalid cell value");
        }
        if (type == String.class) {
            return toText(cell);
        }
        if (type == Long.class || type == Integer.class) {
            return toWholeNumber(cell, type);
        }
        if (type == LocalDateTime.class) {
            return toDateTime(cell);
        }
        if (type == LocalDate.class) {
            return cell.date() != null
                    ? cell.date().toLocalDate()
                    : parse(cell, LocalDate::parse, "must be a date (yyyy-MM-dd)");
        }
        return toEnum(cell, type);
    }

    private static String toText(CellValue cell) {
        return switch (cell.kind()) {
            case NUMERIC -> {
                // Số ≥ 10^15 (vd ID 16–19 chữ số) đã mất độ chính xác trong Excel → bắt nhập dạng text,
                // tránh upsert nhầm sang bài khác
                if (Math.abs(cell.number()) >= MAX_EXACT_EXCEL_NUMBER) {
                    throw new CellConversionException("numbers with more than 15 digits must be entered as text");
                }
                // externalId gõ dạng số (vd 12345) → "12345" thay vì "12345.0"
                yield NumberToTextConverter.toText(cell.number());
            }
            case BOOLEAN -> String.valueOf("1".equals(cell.text()));
            default -> cell.text().strip();
        };
    }

    private static Object toWholeNumber(CellValue cell, Class<?> type) {
        String message = "must be a whole number";
        long value;
        if (cell.kind() == Kind.NUMERIC) {
            double number = cell.number();
            if (number != Math.rint(number) || Math.abs(number) >= 0x1p63) {
                throw new CellConversionException(message);
            }
            value = (long) number;
        } else if (cell.kind() == Kind.STRING) {
            try {
                value = Long.parseLong(cell.text().strip());
            } catch (NumberFormatException e) {
                throw new CellConversionException(message);
            }
        } else {
            throw new CellConversionException(message);
        }
        if (type == Long.class) {
            return value;
        }
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new CellConversionException(message);
        }
        return (int) value;
    }

    private static LocalDateTime toDateTime(CellValue cell) {
        if (cell.date() != null) {
            return cell.date();
        }
        // Chấp nhận cả "yyyy-MM-dd HH:mm:ss" (cách gõ phổ biến trong Excel) lẫn ISO "yyyy-MM-ddTHH:mm:ss"
        return parse(cell, text -> LocalDateTime.parse(text.replaceFirst(" ", "T")),
                "must be a date-time (yyyy-MM-dd HH:mm:ss)");
    }

    private static Object toEnum(CellValue cell, Class<?> type) {
        Object[] constants = type.getEnumConstants();
        if (cell.kind() == Kind.STRING) {
            String text = cell.text().strip();
            for (Object constant : constants) {
                if (((Enum<?>) constant).name().equalsIgnoreCase(text)) {
                    return constant;
                }
            }
        }
        throw new CellConversionException("must be one of " + Arrays.stream(constants)
                .map(constant -> ((Enum<?>) constant).name())
                .collect(Collectors.joining(", ")));
    }

    private static <R> R parse(CellValue cell, Function<String, R> parser, String message) {
        if (cell.kind() != Kind.STRING) {
            throw new CellConversionException(message);
        }
        try {
            return parser.apply(cell.text().strip());
        } catch (DateTimeParseException e) {
            throw new CellConversionException(message);
        }
    }

    /** Lỗi chuyển kiểu của 1 ô — chỉ dùng nội bộ để gom thành {@link ExcelRowError}. */
    private static final class CellConversionException extends RuntimeException {

        CellConversionException(String message) {
            super(message, null, false, false);
        }
    }
}

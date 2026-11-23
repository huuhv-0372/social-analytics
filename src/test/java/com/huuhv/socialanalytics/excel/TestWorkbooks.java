package com.huuhv.socialanalytics.excel;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Tạo file .xlsx trong bộ nhớ cho test. Mỗi mảng là 1 dòng (dòng đầu thường là header);
 * {@code null} = ô trống, {@link Formula} = ô công thức, {@link LocalDate}/{@link LocalDateTime} = ô kiểu ngày.
 */
public final class TestWorkbooks {

    private TestWorkbooks() {
    }

    public record Formula(String expression) {
    }

    public static byte[] xlsx(Object[]... rows) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Posts");
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));
            for (int r = 0; r < rows.length; r++) {
                if (rows[r] == null) {
                    continue;
                }
                Row row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    Object value = rows[r][c];
                    if (value == null) {
                        continue;
                    }
                    Cell cell = row.createCell(c);
                    switch (value) {
                        case Number number -> cell.setCellValue(number.doubleValue());
                        case LocalDateTime dateTime -> {
                            cell.setCellValue(dateTime);
                            cell.setCellStyle(dateStyle);
                        }
                        case LocalDate date -> {
                            cell.setCellValue(date);
                            cell.setCellStyle(dateStyle);
                        }
                        case Boolean bool -> cell.setCellValue(bool);
                        case Formula formula -> cell.setCellFormula(formula.expression());
                        default -> cell.setCellValue(value.toString());
                    }
                }
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

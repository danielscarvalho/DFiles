package com.dfiles.service;

import com.dfiles.model.TableData;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.regex.Pattern;

/** Writes a {@link TableData} out as a real {@code .xlsx} workbook (one sheet, header row plus
 * data rows) via Apache POI. Unlike every other output format this is binary, not text — callers
 * must write the returned bytes directly rather than treating them as a string. A cell that looks
 * like a plain number is written as an Excel numeric cell rather than text, so it sorts and
 * calculates normally once opened. */
public final class XlsxTableWriter {

    private static final Pattern NUMERIC = Pattern.compile("-?(\\d+(\\.\\d+)?|\\.\\d+)([eE][+-]?\\d+)?");

    private XlsxTableWriter() {}

    public static byte[] write(TableData table) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet("Sheet1");
            Row header = sheet.createRow(0);
            for (int c = 0; c < table.columns().size(); c++) {
                header.createCell(c).setCellValue(table.columns().get(c));
            }
            var rows = table.rows();
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                var values = rows.get(r);
                for (int c = 0; c < table.columns().size(); c++) {
                    String value = c < values.size() ? values.get(c) : "";
                    Cell cell = row.createCell(c);
                    if (!value.isEmpty() && NUMERIC.matcher(value).matches()) {
                        cell.setCellValue(Double.parseDouble(value));
                    } else {
                        cell.setCellValue(value);
                    }
                }
            }
            for (int c = 0; c < table.columns().size(); c++) sheet.autoSizeColumn(c);

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            workbook.write(bytes);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write Excel output", e);
        }
    }
}

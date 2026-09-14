package com.dfiles.service;

import com.dfiles.model.TableData;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the first sheet of an {@code .xlsx} workbook into a {@link TableData} via Apache POI,
 * treating the first non-empty row as the header. Every cell is read through a
 * {@link DataFormatter}, so numbers and dates come out as the text Excel would display (e.g.
 * {@code 1234.5}, {@code 2026-01-05}) rather than raw doubles or serial date numbers.
 *
 * <p>Never touches the source file — POI opens the given bytes read-only, and nothing is written
 * back to them.
 */
public final class XlsxTableParser {

    private XlsxTableParser() {}

    /** @throws IOException if the bytes aren't a readable Excel workbook (including encrypted or
     *                       corrupt files, which POI reports as a runtime exception this wraps) */
    public static TableData parse(byte[] xlsxBytes) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(xlsxBytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            int firstRowNum = sheet.getFirstRowNum();
            int lastRowNum = sheet.getLastRowNum();
            if (firstRowNum < 0 || firstRowNum > lastRowNum) return new TableData(List.of(), List.of());

            DataFormatter formatter = new DataFormatter();
            Row headerRow = sheet.getRow(firstRowNum);
            int columnCount = headerRow.getLastCellNum();
            if (columnCount < 0) return new TableData(List.of(), List.of());

            List<String> columns = new ArrayList<>(columnCount);
            for (int c = 0; c < columnCount; c++) {
                String text = formatter.formatCellValue(headerRow.getCell(c, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK)).strip();
                columns.add(text.isEmpty() ? "column_" + (c + 1) : text);
            }

            List<List<String>> rows = new ArrayList<>();
            for (int r = firstRowNum + 1; r <= lastRowNum; r++) {
                Row row = sheet.getRow(r);
                List<String> values = new ArrayList<>(columnCount);
                for (int c = 0; c < columnCount; c++) {
                    Cell cell = row == null ? null : row.getCell(c, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                    values.add(cell == null ? "" : formatter.formatCellValue(cell));
                }
                rows.add(values);
            }
            return new TableData(columns, rows);
        } catch (RuntimeException e) {
            throw new IOException("Could not read Excel file: " + e.getMessage(), e);
        }
    }
}

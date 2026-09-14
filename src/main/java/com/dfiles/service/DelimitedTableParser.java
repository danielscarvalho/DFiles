package com.dfiles.service;

import com.dfiles.model.TableData;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses CSV and TSV text into a {@link TableData}, treating the first line as the header row.
 *
 * <p>CSV parsing is delegated to Apache Commons CSV ({@link CSVFormat#DEFAULT}), which correctly
 * handles double-quoted fields — including ones containing a literal line break, which a
 * line-by-line regex split cannot. TSV has no standard quoting convention (a literal tab inside a
 * field isn't expected), so it's split with a plain regex instead of pulling in a library for it.
 */
public final class DelimitedTableParser {

    private static final Pattern TSV_SPLIT = Pattern.compile("\t");

    private DelimitedTableParser() {}

    /** @throws IOException if the text isn't parseable as CSV (Commons CSV surfaces malformed
     *                       quoting, e.g. an unterminated quoted field, this way) */
    public static TableData parseCsv(String text) throws IOException {
        try (CSVParser parser = CSVParser.parse(text, CSVFormat.DEFAULT)) {
            List<CSVRecord> records = parser.getRecords();
            if (records.isEmpty()) return new TableData(List.of(), List.of());

            List<String> columns = new ArrayList<>();
            records.get(0).forEach(columns::add);

            List<List<String>> rows = new ArrayList<>();
            for (int i = 1; i < records.size(); i++) {
                CSVRecord record = records.get(i);
                List<String> row = new ArrayList<>(columns.size());
                for (int c = 0; c < columns.size(); c++) {
                    row.add(c < record.size() ? record.get(c) : "");
                }
                rows.add(row);
            }
            return new TableData(columns, rows);
        }
    }

    /** Parses tab-separated text with no quoting. */
    public static TableData parseTsv(String text) {
        String[] lines = text.split("\\r\\n|\\r|\\n", -1);
        List<String[]> nonBlank = new ArrayList<>();
        for (String line : lines) {
            if (!line.isEmpty()) nonBlank.add(TSV_SPLIT.split(line, -1));
        }
        if (nonBlank.isEmpty()) return new TableData(List.of(), List.of());

        List<String> columns = new ArrayList<>(List.of(nonBlank.get(0)));
        List<List<String>> rows = new ArrayList<>();
        for (int i = 1; i < nonBlank.size(); i++) {
            String[] fields = nonBlank.get(i);
            List<String> row = new ArrayList<>(columns.size());
            for (int c = 0; c < columns.size(); c++) {
                row.add(c < fields.length ? fields[c] : "");
            }
            rows.add(row);
        }
        return new TableData(columns, rows);
    }
}

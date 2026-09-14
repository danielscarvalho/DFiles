package com.dfiles.service;

import com.dfiles.model.TableData;

import java.util.regex.Pattern;

/** Renders a {@link TableData} as standard comma-separated CSV text, quoting only the fields
 * that need it (containing a comma, double quote, or line break) and doubling any embedded
 * quotes — the same convention {@link DelimitedTableParser} reads back. */
public final class CsvTableWriter {

    private static final Pattern NEEDS_QUOTING = Pattern.compile(".*[\",\r\n].*", Pattern.DOTALL);

    private CsvTableWriter() {}

    public static String write(TableData table) {
        StringBuilder csv = new StringBuilder();
        appendRow(csv, table.columns());
        for (var row : table.rows()) appendRow(csv, row);
        return csv.toString();
    }

    private static void appendRow(StringBuilder csv, java.util.List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) csv.append(',');
            csv.append(quoteIfNeeded(values.get(i)));
        }
        csv.append("\r\n");
    }

    private static String quoteIfNeeded(String value) {
        if (!NEEDS_QUOTING.matcher(value).matches()) return value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}

package com.dfiles.service;

import com.dfiles.model.TableData;

import java.util.List;

/** Renders a {@link TableData} as tab-separated text, the same no-quoting convention
 * {@link DelimitedTableParser#parseTsv} reads back — a cell containing a literal tab or line
 * break isn't escaped, matching how TSV is conventionally produced. */
public final class TsvTableWriter {

    private TsvTableWriter() {}

    public static String write(TableData table) {
        StringBuilder tsv = new StringBuilder();
        appendRow(tsv, table.columns());
        for (var row : table.rows()) appendRow(tsv, row);
        return tsv.toString();
    }

    private static void appendRow(StringBuilder tsv, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) tsv.append('\t');
            tsv.append(values.get(i));
        }
        tsv.append("\r\n");
    }
}

package com.dfiles.service;

import com.dfiles.model.TableData;

/** Renders a {@link TableData} as a GitHub-Flavored-Markdown pipe table, the same shape
 * {@link MarkdownTableParser} reads. A cell's literal {@code |} is escaped as {@code \|}, and any
 * line break within a cell is collapsed to a space, since a Markdown table row must be one
 * physical line. */
public final class MarkdownTableWriter {

    private MarkdownTableWriter() {}

    public static String write(TableData table) {
        StringBuilder md = new StringBuilder();
        appendRow(md, table.columns());
        md.append("|");
        for (int i = 0; i < table.columns().size(); i++) md.append(" --- |");
        md.append("\n");
        for (var row : table.rows()) appendRow(md, row);
        return md.toString();
    }

    private static void appendRow(StringBuilder md, java.util.List<String> values) {
        md.append("|");
        for (String value : values) {
            md.append(" ").append(escapeCell(value)).append(" |");
        }
        md.append("\n");
    }

    private static String escapeCell(String value) {
        return value.replace("|", "\\|").replace("\r\n", " ").replace("\n", " ").replace("\r", " ");
    }
}

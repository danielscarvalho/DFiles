package com.dfiles.service;

import com.dfiles.model.TableData;

/** Renders a {@link TableData} as a standalone HTML {@code <table>} with a {@code <thead>} header
 * row and one {@code <tbody>} row per record, escaping every cell's text content. */
public final class HtmlTableWriter {

    private HtmlTableWriter() {}

    public static String write(TableData table) {
        StringBuilder html = new StringBuilder();
        html.append("<table>\n  <thead>\n    <tr>\n");
        for (String column : table.columns()) {
            html.append("      <th>").append(escape(column)).append("</th>\n");
        }
        html.append("    </tr>\n  </thead>\n  <tbody>\n");
        for (var row : table.rows()) {
            html.append("    <tr>\n");
            for (int c = 0; c < table.columns().size(); c++) {
                String value = c < row.size() ? row.get(c) : "";
                html.append("      <td>").append(escape(value)).append("</td>\n");
            }
            html.append("    </tr>\n");
        }
        html.append("  </tbody>\n</table>\n");
        return html.toString();
    }

    private static String escape(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}

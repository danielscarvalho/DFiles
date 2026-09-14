package com.dfiles.service;

import com.dfiles.model.TableData;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Renders a {@link TableData} as the "list of flat mappings" YAML shape {@link YamlTableParser}
 * reads back — one {@code -}-prefixed block per row, one {@code key: value} line per column. */
public final class YamlTableWriter {

    private static final Pattern NUMERIC = Pattern.compile("-?(\\d+(\\.\\d+)?|\\.\\d+)([eE][+-]?\\d+)?");
    private static final Pattern NEEDS_QUOTING = Pattern.compile(".*[:#\\[\\]{},&*!|>'\"%@`].*|^[\\s-?].*|.*\\s$");
    private static final Set<String> RESERVED_WORDS = Set.of("null", "~", "true", "false", "yes", "no", "on", "off");

    private YamlTableWriter() {}

    public static String write(TableData table) {
        List<String> columns = table.columns();
        StringBuilder yaml = new StringBuilder();
        for (List<String> row : table.rows()) {
            boolean first = true;
            for (int c = 0; c < columns.size(); c++) {
                String value = c < row.size() ? row.get(c) : "";
                yaml.append(first ? "- " : "  ").append(columns.get(c)).append(": ").append(scalar(value)).append("\n");
                first = false;
            }
        }
        return yaml.toString();
    }

    private static String scalar(String value) {
        if (value.isEmpty()) return "\"\"";
        if (NUMERIC.matcher(value).matches()) return value; // preserve as a YAML number for other consumers
        if (RESERVED_WORDS.contains(value.toLowerCase()) || NEEDS_QUOTING.matcher(value).matches() || value.contains("\n")) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
        }
        return value;
    }
}

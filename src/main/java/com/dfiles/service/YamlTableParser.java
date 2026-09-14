package com.dfiles.service;

import com.dfiles.model.TableData;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the one YAML shape that maps naturally onto a table — a sequence of flat mappings, the
 * same "list of records" pattern JSON uses for tabular data:
 *
 * <pre>{@code
 * - name: Alice
 *   age: 30
 *   city: NYC
 * - name: Bob
 *   age: 25
 * }</pre>
 *
 * <p>This is a small line-oriented, regex-based subset of YAML, not a general parser: a value
 * that is itself a nested mapping or sequence, flow-style collections ({@code {a: 1}} /
 * {@code [1, 2]}), multi-line scalars ({@code |} / {@code >}), anchors/aliases, and multi-document
 * files are all out of scope. A single top-level mapping (no leading {@code -} sequence markers
 * at all) is accepted as a convenience and treated as one row, mirroring how
 * {@link JsonTableParser} treats a lone JSON object.
 */
public final class YamlTableParser {

    private static final Pattern LIST_ITEM = Pattern.compile("^-\\s*(.*)$");
    private static final Pattern KEY_VALUE = Pattern.compile("^([^:\\s][^:]*):\\s?(.*)$");

    private YamlTableParser() {}

    /** @throws IOException if no key/value content is found anywhere in the document */
    public static TableData parse(String text) throws IOException {
        List<Map<String, String>> records = new ArrayList<>();
        Map<String, String> current = null;

        for (String rawLine : text.split("\\r\\n|\\r|\\n", -1)) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#") || line.equals("---") || line.equals("...")) continue;

            Matcher listMatch = LIST_ITEM.matcher(line);
            if (listMatch.matches()) {
                if (current != null) records.add(current);
                current = new LinkedHashMap<>();
                String rest = listMatch.group(1);
                if (!rest.isEmpty()) addField(current, rest);
                continue;
            }

            if (current == null) current = new LinkedHashMap<>(); // bare top-level mapping, no "- " seen yet
            addField(current, line);
        }
        if (current != null) records.add(current);

        if (records.isEmpty() || records.stream().allMatch(Map::isEmpty)) {
            throw new IOException("No 'key: value' fields found — only a flat list of flat mappings is supported");
        }

        Set<String> columnSet = new LinkedHashSet<>();
        for (Map<String, String> record : records) columnSet.addAll(record.keySet());
        List<String> columns = new ArrayList<>(columnSet);

        List<List<String>> rows = new ArrayList<>();
        for (Map<String, String> record : records) {
            List<String> row = new ArrayList<>(columns.size());
            for (String column : columns) row.add(record.getOrDefault(column, ""));
            rows.add(row);
        }
        return new TableData(columns, rows);
    }

    private static void addField(Map<String, String> record, String line) {
        Matcher m = KEY_VALUE.matcher(line);
        if (!m.matches()) return; // not a "key: value" line (e.g. unsupported nested content) — skip it
        record.put(m.group(1).strip(), unquote(m.group(2).strip()));
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && ((value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"')
                || (value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\''))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}

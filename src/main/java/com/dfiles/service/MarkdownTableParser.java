package com.dfiles.service;

import com.dfiles.model.TableData;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses a GitHub-Flavored-Markdown pipe table (the kind rendered by GitHub, GitLab, and most
 * Markdown editors) into a {@link TableData}. Only the table itself is recognized — any
 * surrounding prose is ignored, and the first pipe-containing line found is taken as the header.
 *
 * <pre>{@code
 * | Name  | Age |
 * | ----- | --- |
 * | Alice | 30  |
 * | Bob   | 25  |
 * }</pre>
 */
public final class MarkdownTableParser {

    private static final Pattern SEPARATOR_ROW = Pattern.compile("^\\s*\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)*\\|?\\s*$");
    private static final Pattern UNESCAPED_PIPE = Pattern.compile("(?<!\\\\)\\|");
    private static final Pattern ESCAPED_PIPE = Pattern.compile("\\\\\\|");

    private MarkdownTableParser() {}

    /**
     * @throws IOException if no line containing {@code |} is found, or the line right after the
     *                      header isn't a valid {@code |---|---|}-style separator row
     */
    public static TableData parse(String text) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\\r\\n|\\r|\\n", -1)) {
            if (line.contains("|")) lines.add(line);
        }
        if (lines.isEmpty()) {
            throw new IOException("No Markdown table found (no line contains '|')");
        }
        if (lines.size() < 2 || !SEPARATOR_ROW.matcher(lines.get(1)).matches()) {
            throw new IOException("Expected a '|---|---|' separator row right after the header row");
        }

        List<String> columns = splitRow(lines.get(0));
        List<List<String>> rows = new ArrayList<>();
        for (int i = 2; i < lines.size(); i++) {
            List<String> fields = splitRow(lines.get(i));
            List<String> row = new ArrayList<>(columns.size());
            for (int c = 0; c < columns.size(); c++) {
                row.add(c < fields.size() ? fields.get(c) : "");
            }
            rows.add(row);
        }
        return new TableData(columns, rows);
    }

    private static List<String> splitRow(String line) {
        String trimmed = line.strip();
        if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("|")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        List<String> cells = new ArrayList<>();
        for (String cell : UNESCAPED_PIPE.split(trimmed, -1)) {
            cells.add(ESCAPED_PIPE.matcher(cell.strip()).replaceAll("|"));
        }
        return cells;
    }
}

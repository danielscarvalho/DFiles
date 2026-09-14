package com.dfiles.service;

import com.dfiles.model.TableData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Renders a {@link TableData} as SQL: an optional {@code CREATE OR REPLACE TABLE} statement with
 * an inferred column type per column, then the whole data set as a single
 * {@code INSERT INTO ... VALUES} statement wrapped in a transaction
 * ({@code BEGIN TRANSACTION; ... COMMIT;}) so a partial failure partway through a large insert
 * doesn't leave the table half-populated.
 *
 * <p>{@code BEGIN TRANSACTION} / {@code COMMIT} work as-is on SQLite and PostgreSQL; MySQL accepts
 * {@code BEGIN} as an alias for {@code START TRANSACTION}. {@code CREATE OR REPLACE TABLE} is
 * supported by MariaDB, DuckDB, and Snowflake, but not by SQLite or standard MySQL/PostgreSQL —
 * on those, drop the table first (or leave the CREATE statement out entirely, which this writer
 * supports via {@code includeCreateTable}, for loading into a table that already exists).
 */
public final class SqlTableWriter {

    private static final Pattern INVALID_IDENTIFIER_CHARS = Pattern.compile("[^A-Za-z0-9_]");
    private static final Pattern INTEGER_VALUE = Pattern.compile("[+-]?\\d+");
    private static final Pattern REAL_VALUE = Pattern.compile("[+-]?(\\d+\\.\\d+|\\.\\d+)([eE][+-]?\\d+)?");

    private enum ColumnType { INTEGER, REAL, TEXT }

    private SqlTableWriter() {}

    /**
     * @param tableName          requested table name; sanitized into a valid SQL identifier
     *                           (falling back to {@code "converted_table"} if nothing usable
     *                           remains)
     * @param includeCreateTable whether to emit the {@code CREATE OR REPLACE TABLE} statement;
     *                           set to {@code false} when inserting into a table that already
     *                           exists
     */
    public static String write(TableData table, String tableName, boolean includeCreateTable) {
        String safeTable = sanitizeIdentifier(tableName, "converted_table");
        List<String> columns = dedupeIdentifiers(table.columns());
        List<ColumnType> types = inferColumnTypes(table);

        StringBuilder sql = new StringBuilder();
        if (includeCreateTable) {
            sql.append("CREATE OR REPLACE TABLE ").append(safeTable).append(" (\n");
            for (int i = 0; i < columns.size(); i++) {
                sql.append("    ").append(columns.get(i)).append(' ').append(types.get(i).name());
                sql.append(i < columns.size() - 1 ? ",\n" : "\n");
            }
            sql.append(");\n\n");
        }

        List<List<String>> rows = table.rows();
        sql.append("BEGIN TRANSACTION;\n");
        if (!rows.isEmpty()) {
            sql.append("INSERT INTO ").append(safeTable).append(" (")
                    .append(String.join(", ", columns)).append(") VALUES\n");
            for (int r = 0; r < rows.size(); r++) {
                List<String> row = rows.get(r);
                sql.append("    (");
                for (int c = 0; c < columns.size(); c++) {
                    sql.append(formatValue(c < row.size() ? row.get(c) : "", types.get(c)));
                    if (c < columns.size() - 1) sql.append(", ");
                }
                sql.append(r < rows.size() - 1 ? "),\n" : ");\n");
            }
        }
        sql.append("COMMIT;\n");
        return sql.toString();
    }

    private static List<ColumnType> inferColumnTypes(TableData table) {
        List<ColumnType> types = new ArrayList<>();
        for (int c = 0; c < table.columns().size(); c++) {
            boolean sawValue = false, allInteger = true, allReal = true;
            for (List<String> row : table.rows()) {
                if (c >= row.size()) continue;
                String value = row.get(c);
                if (value.isEmpty()) continue;
                sawValue = true;
                if (!INTEGER_VALUE.matcher(value).matches()) allInteger = false;
                if (!INTEGER_VALUE.matcher(value).matches() && !REAL_VALUE.matcher(value).matches()) allReal = false;
            }
            types.add(!sawValue ? ColumnType.TEXT : allInteger ? ColumnType.INTEGER : allReal ? ColumnType.REAL : ColumnType.TEXT);
        }
        return types;
    }

    private static String formatValue(String value, ColumnType type) {
        if (value.isEmpty()) return "NULL";
        if (type != ColumnType.TEXT) return value;
        return "'" + value.replace("'", "''") + "'";
    }

    /** Sanitizes every column name and de-duplicates any that collide (case-insensitively) after
     * sanitization by appending {@code _2}, {@code _3}, etc. */
    private static List<String> dedupeIdentifiers(List<String> names) {
        List<String> result = new ArrayList<>(names.size());
        Set<String> seen = new HashSet<>();
        int anonymousIndex = 1;
        for (String name : names) {
            String base = sanitizeIdentifier(name, "column_" + anonymousIndex++);
            String candidate = base;
            int suffix = 2;
            while (!seen.add(candidate.toLowerCase())) {
                candidate = base + "_" + suffix++;
            }
            result.add(candidate);
        }
        return result;
    }

    private static String sanitizeIdentifier(String raw, String fallback) {
        String cleaned = raw == null ? "" : INVALID_IDENTIFIER_CHARS.matcher(raw.strip()).replaceAll("_");
        if (cleaned.isEmpty() || cleaned.chars().allMatch(ch -> ch == '_')) return fallback;
        if (Character.isDigit(cleaned.charAt(0))) cleaned = "_" + cleaned;
        return cleaned;
    }
}

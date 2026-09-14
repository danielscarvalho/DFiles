package com.dfiles.service;

import com.dfiles.model.TableData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Renders a {@link TableData} as SQL for a specific {@link SqlDialect}: an optional "drop table
 * if it exists, then create it" pair (spelled however that dialect requires) with an inferred
 * column type per column, then the data wrapped in a transaction. Row batching and the
 * {@code INSERT} shape itself are dialect-dependent — see {@link SqlDialect} for why (in short:
 * MS SQL Server caps a single {@code INSERT ... VALUES} at 1000 rows, and Oracle has no multi-row
 * {@code VALUES} list at all, so it gets rewritten as {@code INSERT ALL} instead).
 */
public final class SqlTableWriter {

    private static final Pattern INVALID_IDENTIFIER_CHARS = Pattern.compile("[^A-Za-z0-9_]");
    private static final Pattern INTEGER_VALUE = Pattern.compile("[+-]?\\d+");
    private static final Pattern REAL_VALUE = Pattern.compile("[+-]?(\\d+\\.\\d+|\\.\\d+)([eE][+-]?\\d+)?");

    private SqlTableWriter() {}

    /**
     * @param tableName          requested table name; sanitized into a valid identifier for
     *                           {@code dialect} (falling back to {@code "converted_table"} if
     *                           nothing usable remains, and truncated to the dialect's identifier
     *                           length limit)
     * @param includeCreateTable whether to emit the drop/create statements; set to {@code false}
     *                           when inserting into a table that already exists
     */
    public static String write(TableData table, String tableName, boolean includeCreateTable, SqlDialect dialect) {
        String safeTable = sanitizeIdentifier(tableName, "converted_table", dialect.maxIdentifierLength());
        List<String> columns = dedupeIdentifiers(table.columns(), dialect.maxIdentifierLength());
        List<SqlColumnType> types = inferColumnTypes(table);

        StringBuilder sql = new StringBuilder();
        if (includeCreateTable) {
            sql.append(dialect.dropTableIfExists(safeTable));
            sql.append("CREATE TABLE ").append(safeTable).append(" (\n");
            for (int i = 0; i < columns.size(); i++) {
                sql.append("    ").append(columns.get(i)).append(' ').append(dialect.typeName(types.get(i)));
                sql.append(i < columns.size() - 1 ? ",\n" : "\n");
            }
            sql.append(");\n\n");
        }

        List<List<String>> rows = table.rows();
        String begin = dialect.beginTransaction();
        if (!begin.isEmpty()) sql.append(begin);

        if (!rows.isEmpty()) {
            if (dialect.supportsMultiRowValues()) {
                appendBatchedValuesInserts(sql, safeTable, columns, types, rows, dialect.maxRowsPerBatch());
            } else {
                appendInsertAll(sql, safeTable, columns, types, rows, dialect.maxRowsPerBatch());
            }
        }
        sql.append("COMMIT;\n");
        return sql.toString();
    }

    /** Standard form: one or more {@code INSERT INTO t (...) VALUES (...), (...);} statements,
     * each covering up to {@code maxRowsPerBatch} rows (a single statement covering every row
     * when the dialect has no such limit). */
    private static void appendBatchedValuesInserts(StringBuilder sql, String table, List<String> columns,
                                                     List<SqlColumnType> types, List<List<String>> rows, int maxRowsPerBatch) {
        for (int start = 0; start < rows.size(); start += maxRowsPerBatch) {
            int end = Math.min(start + maxRowsPerBatch, rows.size());
            sql.append("INSERT INTO ").append(table).append(" (")
                    .append(String.join(", ", columns)).append(") VALUES\n");
            for (int r = start; r < end; r++) {
                appendValuesTuple(sql, "    ", columns, types, rows.get(r));
                sql.append(r < end - 1 ? ",\n" : ";\n");
            }
        }
    }

    /** Oracle has no multi-row {@code VALUES} list, so each batch becomes one
     * {@code INSERT ALL INTO t (...) VALUES (...) INTO t (...) VALUES (...) ... SELECT 1 FROM DUAL;}
     * statement instead — Oracle's standard idiom for a single-statement bulk literal insert. */
    private static void appendInsertAll(StringBuilder sql, String table, List<String> columns,
                                         List<SqlColumnType> types, List<List<String>> rows, int maxRowsPerBatch) {
        String columnList = String.join(", ", columns);
        for (int start = 0; start < rows.size(); start += maxRowsPerBatch) {
            int end = Math.min(start + maxRowsPerBatch, rows.size());
            sql.append("INSERT ALL\n");
            for (int r = start; r < end; r++) {
                sql.append("    INTO ").append(table).append(" (").append(columnList).append(") VALUES ");
                appendValuesTuple(sql, "", columns, types, rows.get(r));
                sql.append("\n");
            }
            sql.append("SELECT 1 FROM DUAL;\n");
        }
    }

    private static void appendValuesTuple(StringBuilder sql, String indent, List<String> columns,
                                           List<SqlColumnType> types, List<String> row) {
        sql.append(indent).append("(");
        for (int c = 0; c < columns.size(); c++) {
            sql.append(formatValue(c < row.size() ? row.get(c) : "", types.get(c)));
            if (c < columns.size() - 1) sql.append(", ");
        }
        sql.append(")");
    }

    private static List<SqlColumnType> inferColumnTypes(TableData table) {
        List<SqlColumnType> types = new ArrayList<>();
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
            types.add(!sawValue ? SqlColumnType.TEXT : allInteger ? SqlColumnType.INTEGER : allReal ? SqlColumnType.REAL : SqlColumnType.TEXT);
        }
        return types;
    }

    private static String formatValue(String value, SqlColumnType type) {
        if (value.isEmpty()) return "NULL";
        if (type != SqlColumnType.TEXT) return value;
        return "'" + value.replace("'", "''") + "'";
    }

    /** Sanitizes every column name for {@code dialect} and de-duplicates any that collide
     * (case-insensitively) after sanitization by appending {@code _2}, {@code _3}, etc. */
    private static List<String> dedupeIdentifiers(List<String> names, int maxLength) {
        List<String> result = new ArrayList<>(names.size());
        Set<String> seen = new HashSet<>();
        int anonymousIndex = 1;
        for (String name : names) {
            String base = sanitizeIdentifier(name, "column_" + anonymousIndex++, maxLength);
            String candidate = base;
            int suffix = 2;
            while (!seen.add(candidate.toLowerCase())) {
                String suffixStr = "_" + suffix++;
                candidate = truncate(base, maxLength - suffixStr.length()) + suffixStr;
            }
            result.add(candidate);
        }
        return result;
    }

    private static String sanitizeIdentifier(String raw, String fallback, int maxLength) {
        String cleaned = raw == null ? "" : INVALID_IDENTIFIER_CHARS.matcher(raw.strip()).replaceAll("_");
        if (cleaned.isEmpty() || cleaned.chars().allMatch(ch -> ch == '_')) return truncate(fallback, maxLength);
        if (Character.isDigit(cleaned.charAt(0))) cleaned = "_" + cleaned;
        return truncate(cleaned, maxLength);
    }

    private static String truncate(String s, int maxLength) {
        return s.length() <= maxLength ? s : s.substring(0, Math.max(1, maxLength));
    }
}

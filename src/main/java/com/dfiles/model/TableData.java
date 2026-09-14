package com.dfiles.model;

import java.util.List;

/**
 * A generic in-memory table — column names plus rows of string cell values — used as the common
 * intermediate form for the Convert feature: every supported input format (CSV, TSV, JSON,
 * Markdown table, YAML, XLSX) is parsed into a {@code TableData}, and every supported output
 * format (SQL, CSV) is written from one. This is what lets any input format convert to any
 * output format without an input/output-specific code path for each combination.
 *
 * <p>Cells are always strings; {@link com.dfiles.service.SqlTableWriter} is responsible for any
 * type inference (numeric vs. text) needed when generating {@code CREATE TABLE} statements. A
 * missing value (e.g. a JSON object lacking a key other rows have) is represented as {@code ""},
 * not {@code null} — callers that need to distinguish "empty" from "absent" should track that
 * separately.
 *
 * @param columns column names, in display order
 * @param rows    each row's cell values, in the same order as {@code columns}; every row is
 *                expected to have exactly {@code columns.size()} entries
 */
public record TableData(List<String> columns, List<List<String>> rows) {
}

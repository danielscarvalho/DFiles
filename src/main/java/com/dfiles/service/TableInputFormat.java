package com.dfiles.service;

/**
 * Formats {@link TableConverter} can parse into a {@link com.dfiles.model.TableData}.
 * {@link #AUTO} is a UI-facing pseudo-format: {@link TableConverter} resolves it to one of the
 * concrete formats by sniffing the input before parsing, it is never itself a parser target.
 */
public enum TableInputFormat {
    AUTO("Auto-detect"),
    CSV("CSV"),
    TSV("TSV"),
    JSON("JSON"),
    MARKDOWN_TABLE("Markdown table"),
    YAML("YAML"),
    XLSX("Excel (.xlsx)");

    private final String displayName;

    TableInputFormat(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() { return displayName; }

    @Override
    public String toString() { return displayName; }
}

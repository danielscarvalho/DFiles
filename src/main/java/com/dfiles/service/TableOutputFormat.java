package com.dfiles.service;

/** Formats {@link TableConverter} can write a {@link com.dfiles.model.TableData} out as.
 * {@link #XLSX} is binary — everything else is text. */
public enum TableOutputFormat {
    SQL("SQL"),
    CSV("CSV"),
    JSON("JSON"),
    XML("XML"),
    HTML_TABLE("HTML Table"),
    MARKDOWN_TABLE("Markdown Table"),
    YAML("YAML"),
    XLSX("Excel (.xlsx)");

    private final String displayName;

    TableOutputFormat(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() { return displayName; }
    public boolean isBinary() { return this == XLSX; }

    @Override
    public String toString() { return displayName; }
}

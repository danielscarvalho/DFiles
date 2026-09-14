package com.dfiles.service;

/** Formats {@link TableConverter} can write a {@link com.dfiles.model.TableData} out as. */
public enum TableOutputFormat {
    SQL("SQL"),
    CSV("CSV");

    private final String displayName;

    TableOutputFormat(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() { return displayName; }

    @Override
    public String toString() { return displayName; }
}

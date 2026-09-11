package com.dfiles.model;

import java.nio.file.Path;

/** An entry rendered in the left places panel: either a built-in standard folder or a user bookmark. */
public class PlaceEntry {

    public enum Kind { STANDARD, CUSTOM, ROOT }

    private final String label;
    private final Path path;
    private final String icon;
    private final Kind kind;
    private final Integer customFolderId;

    public PlaceEntry(String label, Path path, String icon, Kind kind, Integer customFolderId) {
        this.label = label;
        this.path = path;
        this.icon = icon;
        this.kind = kind;
        this.customFolderId = customFolderId;
    }

    public static PlaceEntry standard(String label, Path path, String icon) {
        return new PlaceEntry(label, path, icon, Kind.STANDARD, null);
    }

    public static PlaceEntry custom(String label, Path path, int id) {
        return new PlaceEntry(label, path, "🗀", Kind.CUSTOM, id);
    }

    public static PlaceEntry root(String label, Path path) {
        return new PlaceEntry(label, path, "🖴", Kind.ROOT, null);
    }

    public String getLabel() { return label; }
    public Path getPath() { return path; }
    public String getIcon() { return icon; }
    public Kind getKind() { return kind; }
    public Integer getCustomFolderId() { return customFolderId; }
}

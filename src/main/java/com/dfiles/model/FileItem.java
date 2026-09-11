package com.dfiles.model;

import java.nio.file.Path;
import java.util.Objects;

/** A single row shown in the right-hand file table: one filesystem entry. */
public class FileItem {

    private final String name;
    private final Path path;
    private final boolean directory;
    private final long size;
    private final long lastModified;
    private final String typeLabel;
    private final boolean hidden;

    public FileItem(String name, Path path, boolean directory, long size, long lastModified, String typeLabel, boolean hidden) {
        this.name = name;
        this.path = path;
        this.directory = directory;
        this.size = size;
        this.lastModified = lastModified;
        this.typeLabel = typeLabel;
        this.hidden = hidden;
    }

    public String getName() { return name; }
    public Path getPath() { return path; }
    public boolean isDirectory() { return directory; }
    public long getSize() { return size; }
    public long getLastModified() { return lastModified; }
    public String getTypeLabel() { return typeLabel; }
    public boolean isHidden() { return hidden; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FileItem)) return false;
        FileItem fileItem = (FileItem) o;
        return path.equals(fileItem.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path);
    }
}

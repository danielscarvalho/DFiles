package com.dfiles.model;

/** A user-added bookmark/shortcut folder shown in the left panel, persisted in SQLite. */
public class CustomFolder {

    private final int id;
    private final String name;
    private final String path;

    public CustomFolder(int id, String name, String path) {
        this.id = id;
        this.name = name;
        this.path = path;
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public String getPath() { return path; }

    @Override
    public String toString() { return name; }
}

package com.dfiles.model;

/** A named bash script stored in the database, shown in the left panel's Scripts section. */
public class ScriptEntry {

    private final int id;
    private final String name;

    public ScriptEntry(int id, String name) {
        this.id = id;
        this.name = name;
    }

    public int getId() { return id; }
    public String getName() { return name; }

    @Override
    public String toString() { return name; }
}

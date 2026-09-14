package com.dfiles.service;

import com.dfiles.model.TableData;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.regex.Pattern;

/** Renders a {@link TableData} as a pretty-printed JSON array of objects — the same shape
 * {@link JsonTableParser} reads back. A cell that looks like a plain number is written as a JSON
 * number rather than a string, so round-tripping through a JSON-aware tool preserves numeric
 * types; everything else (including empty cells) is a JSON string. */
public final class JsonTableWriter {

    private static final Pattern NUMERIC = Pattern.compile("-?(\\d+(\\.\\d+)?|\\.\\d+)([eE][+-]?\\d+)?");

    private JsonTableWriter() {}

    public static String write(TableData table) {
        JsonArray array = new JsonArray();
        for (var row : table.rows()) {
            JsonObject obj = new JsonObject();
            for (int c = 0; c < table.columns().size(); c++) {
                String value = c < row.size() ? row.get(c) : "";
                if (!value.isEmpty() && NUMERIC.matcher(value).matches()) {
                    obj.addProperty(table.columns().get(c), new java.math.BigDecimal(value));
                } else {
                    obj.addProperty(table.columns().get(c), value);
                }
            }
            array.add(obj);
        }
        return new GsonBuilder().setPrettyPrinting().create().toJson(array);
    }
}

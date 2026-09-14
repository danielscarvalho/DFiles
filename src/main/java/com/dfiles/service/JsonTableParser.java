package com.dfiles.service;

import com.dfiles.model.TableData;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses a JSON array of flat objects (or a single JSON object, treated as one row) into a
 * {@link TableData}. The column list is the union of every object's keys, in the order each key
 * is first seen — rows missing a given key get an empty cell there. A nested object or array
 * value is kept as its compact JSON text rather than flattened, since there's no single obvious
 * column-per-nested-field naming scheme that would work for every shape of input.
 *
 * <p>Uses Gson rather than the {@code org.json} library already used elsewhere in DFiles
 * ({@link com.dfiles.service.AiScriptService}): {@code org.json}'s {@code JSONObject} stores keys
 * in a plain {@link java.util.HashMap}, which does not preserve the order keys appeared in the
 * source — and column order matters for a conversion tool in a way it doesn't for the
 * AI-assistant's use of JSON. Gson's {@code JsonObject} preserves insertion order.
 */
public final class JsonTableParser {

    private JsonTableParser() {}

    /**
     * @throws IOException if the text isn't valid JSON, isn't an array/object at the top level,
     *                      or the array contains a non-object element
     */
    public static TableData parse(String text) throws IOException {
        JsonElement root;
        try {
            root = JsonParser.parseString(text);
        } catch (JsonParseException e) {
            throw new IOException("Invalid JSON: " + e.getMessage(), e);
        }

        JsonArray array;
        if (root.isJsonArray()) {
            array = root.getAsJsonArray();
        } else if (root.isJsonObject()) {
            array = new JsonArray();
            array.add(root);
        } else {
            throw new IOException("Expected a JSON array of objects or a single JSON object at the top level");
        }

        Set<String> columnSet = new LinkedHashSet<>();
        List<JsonObject> objects = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = array.get(i);
            if (!element.isJsonObject()) {
                throw new IOException("Expected every array element to be a JSON object (element " + (i + 1) + " is not)");
            }
            JsonObject obj = element.getAsJsonObject();
            objects.add(obj);
            columnSet.addAll(obj.keySet());
        }

        List<String> columns = new ArrayList<>(columnSet);
        List<List<String>> rows = new ArrayList<>();
        for (JsonObject obj : objects) {
            List<String> row = new ArrayList<>(columns.size());
            for (String column : columns) row.add(cellText(obj.get(column)));
            rows.add(row);
        }
        return new TableData(columns, rows);
    }

    private static String cellText(JsonElement value) {
        if (value == null || value.isJsonNull()) return "";
        if (value.isJsonPrimitive()) return value.getAsString();
        return value.toString(); // nested object/array kept as compact JSON text
    }
}

package com.dfiles.service;

import com.dfiles.model.TableData;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Renders a {@link TableData} as XML — a {@code <table>} root containing one {@code <row>} per
 * record, each with one child element per column — using the JDK's built-in
 * {@link XMLStreamWriter} (StAX) rather than hand-built strings, so text content is always
 * correctly escaped. {@link XmlTableParser} reads this same shape back.
 */
public final class XmlTableWriter {

    private static final Pattern INVALID_NAME_CHARS = Pattern.compile("[^A-Za-z0-9_.-]");
    private static final Pattern STARTS_WITH_XML = Pattern.compile("^(?i)xml.*");

    private XmlTableWriter() {}

    public static String write(TableData table) {
        List<String> columns = dedupeXmlNames(table.columns());
        StringWriter out = new StringWriter();
        try {
            XMLStreamWriter xml = XMLOutputFactory.newInstance().createXMLStreamWriter(out);
            xml.writeStartDocument("UTF-8", "1.0");
            xml.writeCharacters("\n");
            xml.writeStartElement("table");
            xml.writeCharacters("\n");
            for (List<String> row : table.rows()) {
                xml.writeCharacters("  ");
                xml.writeStartElement("row");
                for (int c = 0; c < columns.size(); c++) {
                    String value = c < row.size() ? row.get(c) : "";
                    xml.writeStartElement(columns.get(c));
                    xml.writeCharacters(value);
                    xml.writeEndElement();
                }
                xml.writeEndElement();
                xml.writeCharacters("\n");
            }
            xml.writeEndElement();
            xml.writeEndDocument();
            xml.flush();
            xml.close();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Failed to write XML output", e);
        }
        return out.toString();
    }

    private static List<String> dedupeXmlNames(List<String> names) {
        List<String> result = new ArrayList<>(names.size());
        Set<String> seen = new HashSet<>();
        int anonymousIndex = 1;
        for (String name : names) {
            String base = sanitizeXmlName(name, "column_" + anonymousIndex++);
            String candidate = base;
            int suffix = 2;
            while (!seen.add(candidate.toLowerCase())) {
                candidate = base + "_" + suffix++;
            }
            result.add(candidate);
        }
        return result;
    }

    private static String sanitizeXmlName(String raw, String fallback) {
        String cleaned = raw == null ? "" : INVALID_NAME_CHARS.matcher(raw.strip()).replaceAll("_");
        if (cleaned.isEmpty() || cleaned.chars().allMatch(ch -> ch == '_')) return fallback;
        char first = cleaned.charAt(0);
        if (Character.isDigit(first) || first == '.' || first == '-') cleaned = "_" + cleaned;
        if (STARTS_WITH_XML.matcher(cleaned).matches()) cleaned = "_" + cleaned; // "xml*" names are reserved
        return cleaned;
    }
}

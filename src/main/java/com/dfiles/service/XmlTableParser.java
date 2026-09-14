package com.dfiles.service;

import com.dfiles.model.TableData;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses an XML document shaped like repeated records — most commonly the same shape
 * {@link XmlTableWriter} produces, e.g.
 *
 * <pre>{@code
 * <table>
 *   <row><name>Alice</name><age>30</age></row>
 *   <row><name>Bob</name><age>25</age></row>
 * </table>
 * }</pre>
 *
 * <p>The "row" element name doesn't have to literally be {@code <row>}: whichever tag name is
 * most common among the root's direct child elements is treated as one row per occurrence, so
 * {@code <items><item>...</item><item>...</item></items>} works the same way. Within a row,
 * each direct child element becomes one column (its tag name) with its text content as the
 * value; nested elements below that are flattened to their concatenated text rather than kept as
 * structured data, the same simplification {@link JsonTableParser} makes for nested JSON.
 * Attributes are not read.
 */
public final class XmlTableParser {

    private XmlTableParser() {}

    /** @throws IOException if the text isn't well-formed XML, or the root element has no child
     *                       elements to treat as rows */
    public static TableData parse(String text) throws IOException {
        Document doc;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Hardened against XXE: this app can be pointed at attacker-controlled XML via the
            // Paste/URL tabs, so external entities and DTDs must never be resolved.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            doc = builder.parse(new InputSource(new StringReader(text)));
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Invalid XML: " + e.getMessage(), e);
        }

        Element root = doc.getDocumentElement();
        List<Element> children = childElements(root);
        if (children.isEmpty()) {
            throw new IOException("The root element <" + root.getTagName() + "> has no child elements to read as rows");
        }

        String rowTag = mostCommonTagName(children);
        List<Element> rowElements = new ArrayList<>();
        for (Element child : children) {
            if (child.getTagName().equals(rowTag)) rowElements.add(child);
        }

        Set<String> columnSet = new LinkedHashSet<>();
        List<Map<String, String>> records = new ArrayList<>();
        for (Element rowElement : rowElements) {
            Map<String, String> record = new LinkedHashMap<>();
            for (Element field : childElements(rowElement)) {
                record.put(field.getTagName(), field.getTextContent());
                columnSet.add(field.getTagName());
            }
            records.add(record);
        }

        List<String> columns = new ArrayList<>(columnSet);
        List<List<String>> rows = new ArrayList<>();
        for (Map<String, String> record : records) {
            List<String> row = new ArrayList<>(columns.size());
            for (String column : columns) row.add(record.getOrDefault(column, ""));
            rows.add(row);
        }
        return new TableData(columns, rows);
    }

    private static List<Element> childElements(Element parent) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) result.add((Element) node);
        }
        return result;
    }

    private static String mostCommonTagName(List<Element> elements) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Element e : elements) counts.merge(e.getTagName(), 1, Integer::sum);
        String best = elements.get(0).getTagName();
        int bestCount = 0;
        for (var entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return best;
    }
}

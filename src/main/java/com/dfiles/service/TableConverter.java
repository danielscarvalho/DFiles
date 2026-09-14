package com.dfiles.service;

import com.dfiles.model.TableData;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.regex.Pattern;

/**
 * Facade for the Convert feature: given raw input bytes, a text encoding, an input format (or
 * {@link TableInputFormat#AUTO} to sniff one), and an output format, produces the converted
 * result. Never touches whatever the bytes came from — a pasted string, a local file, or a
 * downloaded URL are all read once into memory and the original is never written back to.
 *
 * <p>The actual parsing and writing is delegated to one small class per format
 * ({@link DelimitedTableParser}, {@link JsonTableParser}, {@link XmlTableParser},
 * {@link MarkdownTableParser}, {@link YamlTableParser}, {@link XlsxTableParser},
 * {@link SqlTableWriter}, {@link CsvTableWriter}, {@link TsvTableWriter}, {@link JsonTableWriter},
 * {@link XmlTableWriter}, {@link HtmlTableWriter}, {@link MarkdownTableWriter},
 * {@link YamlTableWriter}, {@link XlsxTableWriter}); this class only resolves {@code AUTO} and
 * dispatches to the right one, which is what makes every input format able to produce every
 * output format without a dedicated code path per combination.
 *
 * <p>{@link TableOutputFormat#XLSX} is the one output that isn't text — use {@link #writeXlsx}
 * for it instead of {@link #write}.
 */
public final class TableConverter {

    private static final byte[] ZIP_MAGIC = {'P', 'K', 3, 4};
    private static final Pattern YAML_LIKE_LINE = Pattern.compile("^-\\s.*$|^[^:\\-\\s][^:]*:\\s?.*$");

    private TableConverter() {}

    /**
     * Best-effort format sniff, used when the user leaves the input format on Auto-detect.
     * Checked in order: ZIP magic bytes or a {@code .xlsx} name hint (XLSX is binary, so it's
     * checked before anything is decoded as text), then a leading {@code <} for XML, then JSON's
     * leading {@code [}/{@code {}, then a Markdown table's leading {@code |}, then a YAML-shaped
     * {@code key:} or {@code - } line, finally falling back to CSV or TSV by whichever delimiter
     * is more common on the first line.
     */
    public static TableInputFormat detect(byte[] rawBytes, String fileNameHint, Charset charset) {
        if (isZipMagic(rawBytes) || hasExtension(fileNameHint, ".xlsx")) return TableInputFormat.XLSX;

        String text = new String(rawBytes, charset);
        String firstLine = firstNonBlankLine(text);
        String trimmed = text.strip();
        if (trimmed.startsWith("<")) return TableInputFormat.XML;
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) return TableInputFormat.JSON;
        if (firstLine != null && firstLine.strip().startsWith("|")) return TableInputFormat.MARKDOWN_TABLE;
        if (firstLine != null && YAML_LIKE_LINE.matcher(firstLine.strip()).matches()) return TableInputFormat.YAML;
        if (firstLine != null && countChar(firstLine, '\t') > countChar(firstLine, ',')) return TableInputFormat.TSV;
        return TableInputFormat.CSV;
    }

    /** @param format must not be {@link TableInputFormat#AUTO} — resolve it with {@link #detect} first */
    public static TableData parse(byte[] rawBytes, Charset charset, TableInputFormat format) throws IOException {
        if (format == TableInputFormat.XLSX) return XlsxTableParser.parse(rawBytes);
        String text = new String(rawBytes, charset);
        return switch (format) {
            case CSV -> DelimitedTableParser.parseCsv(text);
            case TSV -> DelimitedTableParser.parseTsv(text);
            case JSON -> JsonTableParser.parse(text);
            case XML -> XmlTableParser.parse(text);
            case MARKDOWN_TABLE -> MarkdownTableParser.parse(text);
            case YAML -> YamlTableParser.parse(text);
            case XLSX -> throw new IllegalStateException("handled above");
            case AUTO -> throw new IllegalArgumentException("AUTO must be resolved with detect() first");
        };
    }

    /**
     * @param includeCreateTable ignored for every format except {@link TableOutputFormat#SQL}
     * @param dialect            ignored for every format except {@link TableOutputFormat#SQL}
     * @throws IllegalArgumentException if {@code format} is {@link TableOutputFormat#XLSX} — use
     *                                   {@link #writeXlsx} for that one instead, since its output
     *                                   is bytes, not text
     */
    public static String write(TableData table, TableOutputFormat format, String tableName,
                                boolean includeCreateTable, SqlDialect dialect) {
        return switch (format) {
            case SQL -> SqlTableWriter.write(table, tableName, includeCreateTable, dialect);
            case CSV -> CsvTableWriter.write(table);
            case TSV -> TsvTableWriter.write(table);
            case JSON -> JsonTableWriter.write(table);
            case XML -> XmlTableWriter.write(table);
            case HTML_TABLE -> HtmlTableWriter.write(table);
            case MARKDOWN_TABLE -> MarkdownTableWriter.write(table);
            case YAML -> YamlTableWriter.write(table);
            case XLSX -> throw new IllegalArgumentException("XLSX output is binary — use writeXlsx() instead");
        };
    }

    /** The one binary output format; everything else goes through {@link #write}. */
    public static byte[] writeXlsx(TableData table) {
        return XlsxTableWriter.write(table);
    }

    private static boolean isZipMagic(byte[] bytes) {
        if (bytes.length < ZIP_MAGIC.length) return false;
        for (int i = 0; i < ZIP_MAGIC.length; i++) {
            if (bytes[i] != ZIP_MAGIC[i]) return false;
        }
        return true;
    }

    private static boolean hasExtension(String fileName, String extension) {
        return fileName != null && fileName.toLowerCase().endsWith(extension);
    }

    private static String firstNonBlankLine(String text) {
        for (String line : text.split("\\r\\n|\\r|\\n", -1)) {
            if (!line.isBlank()) return line;
        }
        return null;
    }

    private static int countChar(String text, char c) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == c) count++;
        return count;
    }
}

package com.dfiles.service;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts a "Latin" (continental European) CSV file — {@code ;} as the field separator and
 * {@code ,} as the decimal mark, e.g. {@code "1.234,56"} — into an "English" one:
 * {@code ,} as the field separator and {@code .} as the decimal mark ({@code "1234.56"}).
 *
 * <p>Only cells that look like a properly-formatted Latin number are reformatted; every other
 * cell (including the header row, and any text field that happens to contain a comma) passes
 * through unchanged. This is safe specifically because the source format uses {@code ;} to
 * separate fields — a literal comma appearing in a text cell (e.g. {@code "Smith, John"}) is never
 * a field boundary, so it's never mistaken for one, and it's left alone unless the whole cell
 * value matches the numeric pattern.
 */
public final class LatinCsvConverter {

    /** Optional sign, digits grouped in exactly-3 chunks by {@code .} (or ungrouped), then an
     * optional {@code ,}-decimal part. Matches "1234,56", "1.234.567,89", "1234", "1.234" (a
     * thousands-grouped integer with no decimal part) and rejects anything that isn't a
     * well-formed Latin number, so ordinary text is never misread as one. */
    private static final Pattern LATIN_NUMBER = Pattern.compile("(-?)(\\d+(?:\\.\\d{3})*)(?:,(\\d+))?");

    private LatinCsvConverter() {}

    /** @throws IOException if the text can't be parsed as {@code ;}-delimited CSV */
    public static String convertToEnglish(String latinCsvText) throws IOException {
        CSVFormat latinFormat = CSVFormat.DEFAULT.builder().setDelimiter(';').build();
        StringWriter out = new StringWriter();
        try (CSVParser parser = CSVParser.parse(latinCsvText, latinFormat);
             CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT)) {
            for (CSVRecord record : parser) {
                List<String> converted = new ArrayList<>(record.size());
                for (String cell : record) converted.add(convertCell(cell));
                printer.printRecord(converted);
            }
        }
        return out.toString();
    }

    private static String convertCell(String cell) {
        Matcher m = LATIN_NUMBER.matcher(cell);
        if (!m.matches()) return cell;
        String sign = m.group(1);
        String wholePart = m.group(2).replace(".", ""); // strip thousands separators
        String decimalPart = m.group(3);
        return decimalPart == null ? sign + wholePart : sign + wholePart + "." + decimalPart;
    }
}

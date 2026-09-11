package com.dfiles.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class FileSizeFormatter {

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB", "PB"};
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private FileSizeFormatter() {}

    public static String formatSize(long bytes, boolean directory) {
        if (directory) return "—";
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        exp = Math.min(exp, UNITS.length - 1);
        double value = bytes / Math.pow(1024, exp);
        return String.format(Locale.US, "%.1f %s", value, UNITS[exp]);
    }

    public static String formatDate(long epochMillis) {
        return DATE_FORMAT.format(Instant.ofEpochMilli(epochMillis));
    }
}

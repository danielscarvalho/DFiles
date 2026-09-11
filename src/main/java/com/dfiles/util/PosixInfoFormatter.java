package com.dfiles.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/** Renders a single file's metadata the way `ls -l` would, e.g. "-rw-rw-r--  1 robot robot 2780104 jan  5  2026". */
public final class PosixInfoFormatter {

    private static final Logger LOGGER = LogManager.getLogger(PosixInfoFormatter.class);

    private PosixInfoFormatter() {}

    /** Returns null on platforms without POSIX file attributes (e.g. Windows). */
    public static String format(Path path, Locale locale) {
        try {
            PosixFileAttributes attrs = Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);

            char type = Files.isSymbolicLink(path) ? 'l' : (attrs.isDirectory() ? 'd' : '-');
            String perms = PosixFilePermissions.toString(attrs.permissions());

            long nlink = 1;
            try {
                Map<String, Object> unixAttrs = Files.readAttributes(path, "unix:nlink");
                Object n = unixAttrs.get("nlink");
                if (n instanceof Integer i) nlink = i;
            } catch (IOException | UnsupportedOperationException ignored) {
                // "unix" attribute view unavailable; nlink stays at 1
            }

            String owner = attrs.owner() != null ? attrs.owner().getName() : "?";
            String group = attrs.group() != null ? attrs.group().getName() : "?";
            long size = attrs.size();

            DateTimeFormatter monthFmt = DateTimeFormatter.ofPattern("MMM", locale);
            var modified = attrs.lastModifiedTime().toInstant().atZone(ZoneId.systemDefault());
            String date = String.format(locale, "%s %2d  %d",
                    monthFmt.format(modified).toLowerCase(locale), modified.getDayOfMonth(), modified.getYear());

            return String.format(locale, "%c%s  %d %s %s %d %s", type, perms, nlink, owner, group, size, date);
        } catch (UnsupportedOperationException | IOException e) {
            LOGGER.debug("POSIX attributes unavailable for {}", path, e);
            return null;
        }
    }
}

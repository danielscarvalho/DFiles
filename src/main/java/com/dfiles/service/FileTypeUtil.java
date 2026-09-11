package com.dfiles.service;

import com.dfiles.i18n.I18n;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Maps file extensions to a human-readable type label and a small emoji glyph used as a stand-in icon. */
public final class FileTypeUtil {

    private static final Set<String> IMAGE = Set.of("png", "jpg", "jpeg", "gif", "bmp", "svg", "webp", "ico", "tiff");
    private static final Set<String> AUDIO = Set.of("mp3", "wav", "flac", "ogg", "m4a", "aac", "wma");
    private static final Set<String> VIDEO = Set.of("mp4", "mkv", "avi", "mov", "wmv", "webm", "flv", "m4v");
    private static final Set<String> ARCHIVE = Set.of("zip", "tar", "gz", "rar", "7z", "bz2", "xz", "tgz");
    private static final Set<String> DOCUMENT = Set.of("pdf", "doc", "docx", "odt", "rtf", "xls", "xlsx", "ods", "ppt", "pptx", "odp");
    private static final Set<String> TEXT = Set.of("txt", "md", "log", "csv", "yml", "yaml", "ini", "cfg", "conf");
    private static final Set<String> CODE = Set.of("java", "py", "js", "ts", "c", "cpp", "h", "hpp", "cs", "go", "rs",
            "rb", "php", "html", "css", "json", "xml", "sh", "sql", "kt", "swift");
    // Note: these must resolve (via fontconfig) to a monochrome/outline glyph rather than
    // a color emoji font — JavaFX's text renderer cannot draw color/bitmap emoji glyphs
    // (e.g. Noto Color Emoji) at all, so codepoints like the default-presentation 📁/📄/📝
    // render as blank on Linux even when a comprehensive emoji font is installed. The
    // dingbat-style variants below (Miscellaneous Symbols and Pictographs, text-presentation
    // default) render fine everywhere.
    private static final Map<String, String> EXTENSION_ICON = Map.ofEntries(
            Map.entry("image", "🖼"), Map.entry("audio", "♪"), Map.entry("video", "🎬"),
            Map.entry("archive", "📦"), Map.entry("document", "🗎"), Map.entry("text", "🗒"),
            Map.entry("code", "💻")
    );

    private FileTypeUtil() {}

    public static String label(Path path, boolean directory) {
        if (directory) return I18n.t("type.folder");
        String ext = extension(path);
        if (ext.isEmpty()) return I18n.t("type.file");
        return ext.toUpperCase(Locale.ROOT) + " " + categoryWord(ext);
    }

    private static String categoryWord(String ext) {
        if (IMAGE.contains(ext)) return "Image";
        if (AUDIO.contains(ext)) return "Audio";
        if (VIDEO.contains(ext)) return "Video";
        if (ARCHIVE.contains(ext)) return "Archive";
        if (DOCUMENT.contains(ext)) return "Document";
        if (TEXT.contains(ext)) return "Text";
        if (CODE.contains(ext)) return "Source";
        return "File";
    }

    public static String icon(Path path, boolean directory) {
        if (directory) return "🗀";
        String ext = extension(path);
        if (IMAGE.contains(ext)) return EXTENSION_ICON.get("image");
        if (AUDIO.contains(ext)) return EXTENSION_ICON.get("audio");
        if (VIDEO.contains(ext)) return EXTENSION_ICON.get("video");
        if (ARCHIVE.contains(ext)) return EXTENSION_ICON.get("archive");
        if (DOCUMENT.contains(ext)) return EXTENSION_ICON.get("document");
        if (TEXT.contains(ext)) return EXTENSION_ICON.get("text");
        if (CODE.contains(ext)) return EXTENSION_ICON.get("code");
        return "🗎";
    }

    /** Whether this file is plausibly text — a candidate for the head/tail preview. */
    public static boolean isTextLike(Path path) {
        String ext = extension(path);
        return TEXT.contains(ext) || CODE.contains(ext);
    }

    private static String extension(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}

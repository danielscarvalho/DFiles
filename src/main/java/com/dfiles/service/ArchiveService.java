package com.dfiles.service;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Lists and extracts common archive formats. ZIP/JAR are handled natively via java.util.zip
 * (no extra dependency, works on every platform); TAR variants, RAR and 7z are handled by
 * shelling out to the corresponding command-line tool, since the JDK has no built-in support
 * for them and those tools are the standard way to work with these formats on Linux/macOS.
 */
public final class ArchiveService {

    private static final Logger LOGGER = LogManager.getLogger(ArchiveService.class);
    private static final Set<String> SIMPLE_EXTENSIONS = Set.of("zip", "jar", "tar", "tgz", "tbz2", "txz", "rar", "7z");
    private static final Set<String> COMPOUND_SUFFIXES = Set.of(".tar.gz", ".tar.bz2", ".tar.xz");

    private ArchiveService() {}

    public static boolean isArchive(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String suffix : COMPOUND_SUFFIXES) {
            if (name.endsWith(suffix)) return true;
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        return SIMPLE_EXTENSIONS.contains(name.substring(dot + 1));
    }

    /** Returns a plain-text listing of the archive's entries, one per line. */
    public static String listContents(Path path) throws IOException {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".zip") || name.endsWith(".jar")) {
            return listZip(path);
        }
        if (isTar(name)) {
            return runAndCapture("tar", "-tf", path.toString());
        }
        if (name.endsWith(".rar")) {
            return runAndCapture("unrar", "lb", path.toString());
        }
        if (name.endsWith(".7z")) {
            return runAndCapture("7z", "l", path.toString());
        }
        throw new IOException("Unsupported archive format: " + name);
    }

    /** Extracts directly into destinationDir (i.e. "Extract Here" semantics, not a named subfolder). */
    public static void extractHere(Path archivePath, Path destinationDir) throws IOException {
        String name = archivePath.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".zip") || name.endsWith(".jar")) {
            extractZip(archivePath, destinationDir);
        } else if (isTar(name)) {
            runAndCapture("tar", "-xf", archivePath.toString(), "-C", destinationDir.toString());
        } else if (name.endsWith(".rar")) {
            runAndCapture("unrar", "x", "-y", archivePath.toString(), destinationDir + "/");
        } else if (name.endsWith(".7z")) {
            runAndCapture("7z", "x", "-y", "-o" + destinationDir, archivePath.toString());
        } else {
            throw new IOException("Unsupported archive format: " + name);
        }
        LOGGER.info("Extracted {} into {}", archivePath, destinationDir);
    }

    private static boolean isTar(String lowerCaseName) {
        return lowerCaseName.endsWith(".tar") || lowerCaseName.endsWith(".tgz") || lowerCaseName.endsWith(".tbz2")
                || lowerCaseName.endsWith(".txz") || lowerCaseName.endsWith(".tar.gz")
                || lowerCaseName.endsWith(".tar.bz2") || lowerCaseName.endsWith(".tar.xz");
    }

    private static String listZip(Path path) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (ZipFile zip = new ZipFile(path.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    sb.append(String.format(Locale.US, "%10s  %s%n", "-", entry.getName()));
                } else {
                    sb.append(String.format(Locale.US, "%10d  %s%n", entry.getSize(), entry.getName()));
                }
            }
        }
        return sb.toString();
    }

    private static void extractZip(Path zipPath, Path destDir) throws IOException {
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path target = destDir.resolve(entry.getName()).normalize();
                if (!target.startsWith(destDir)) {
                    // zip-slip protection: refuse entries that try to escape the destination
                    throw new IOException("Archive entry escapes destination folder: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    if (target.getParent() != null) Files.createDirectories(target.getParent());
                    try (InputStream in = zip.getInputStream(entry)) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    private static String runAndCapture(String... command) throws IOException {
        LOGGER.info("Running: {}", String.join(" ", command));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IOException("Required tool not found: " + command[0], e);
        }
        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }
        try {
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IOException(command[0] + " exited with code " + exit + ":\n" + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        return output;
    }

    // ---------------- compression ----------------

    /** Compression formats supported on this machine: ZIP always (built into the JDK),
     * the tar-based ones only if a `tar` binary is on PATH, 7z only if `7z`/`7za` is. */
    public static List<String> availableCompressionFormats() {
        List<String> formats = new ArrayList<>();
        formats.add("zip");
        if (commandExists("tar")) {
            formats.add("tar");
            formats.add("tar.gz");
            formats.add("tar.bz2");
            formats.add("tar.xz");
        }
        if (commandExists("7z") || commandExists("7za")) {
            formats.add("7z");
        }
        return formats;
    }

    private static boolean commandExists(String command) {
        try {
            Process p = new ProcessBuilder(command, "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            p.waitFor();
            return true;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true; // it did start, which is the only thing we're checking
        }
    }

    /** Compresses sourceDir into destinationArchive, storing the folder itself (not just its
     * contents) as the archive's top-level entry, so extracting it recreates the same folder. */
    public static void compress(Path sourceDir, Path destinationArchive, String format) throws IOException {
        Path parent = sourceDir.getParent();
        String folderName = sourceDir.getFileName().toString();
        switch (format) {
            case "zip" -> compressZip(sourceDir, destinationArchive);
            case "tar" -> runAndCapture("tar", "-cf", destinationArchive.toString(), "-C", parent.toString(), folderName);
            case "tar.gz" -> runAndCapture("tar", "-czf", destinationArchive.toString(), "-C", parent.toString(), folderName);
            case "tar.bz2" -> runAndCapture("tar", "-cjf", destinationArchive.toString(), "-C", parent.toString(), folderName);
            case "tar.xz" -> runAndCapture("tar", "-cJf", destinationArchive.toString(), "-C", parent.toString(), folderName);
            case "7z" -> runAndCapture("7z", "a", destinationArchive.toString(), sourceDir.toString());
            default -> throw new IOException("Unsupported compression format: " + format);
        }
        LOGGER.info("Compressed {} into {} ({})", sourceDir, destinationArchive, format);
    }

    private static void compressZip(Path sourceDir, Path destinationZip) throws IOException {
        Path parent = sourceDir.getParent();
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(destinationZip));
             var walk = Files.walk(sourceDir)) {
            for (Path path : (Iterable<Path>) walk.filter(p -> !p.equals(sourceDir))::iterator) {
                String entryName = parent.relativize(path).toString().replace('\\', '/');
                if (Files.isDirectory(path)) {
                    zos.putNextEntry(new ZipEntry(entryName + "/"));
                    zos.closeEntry();
                } else {
                    zos.putNextEntry(new ZipEntry(entryName));
                    Files.copy(path, zos);
                    zos.closeEntry();
                }
            }
        }
    }
}

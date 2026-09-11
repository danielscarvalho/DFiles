package com.dfiles.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reads the first and last N lines of a text file without loading the whole thing into
 * memory — the head comes from a normal line-by-line read that stops early, and the tail
 * comes from seeking near the end of the file and only decoding that trailing chunk.
 */
public final class TextPreviewService {

    public static final int MAX_LINES = 100;
    private static final long MAX_TAIL_SCAN_BYTES = 1_000_000; // 1 MB is generous for ~200 lines of text

    private TextPreviewService() {}

    public static class Preview {
        public final List<String> headLines;
        public final List<String> tailLines;
        public final boolean isFullFile;

        Preview(List<String> headLines, List<String> tailLines, boolean isFullFile) {
            this.headLines = headLines;
            this.tailLines = tailLines;
            this.isFullFile = isFullFile;
        }
    }

    public static Preview read(Path path, int maxLines) throws IOException {
        List<String> head = new ArrayList<>();
        boolean reachedEnd;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8))) {
            String line;
            while (head.size() < maxLines && (line = reader.readLine()) != null) {
                head.add(line);
            }
            reachedEnd = head.size() < maxLines || reader.readLine() == null;
        }
        if (reachedEnd) {
            return new Preview(head, List.of(), true);
        }
        return new Preview(head, readTail(path, maxLines), false);
    }

    private static List<String> readTail(Path path, int maxLines) throws IOException {
        long fileLength = Files.size(path);
        int scanBytes = (int) Math.min(fileLength, MAX_TAIL_SCAN_BYTES);
        byte[] buffer = new byte[scanBytes];
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            raf.seek(fileLength - scanBytes);
            raf.readFully(buffer);
        }
        String text = new String(buffer, StandardCharsets.UTF_8);
        String[] rawLines = text.split("\n", -1);

        // The scan window may start mid-line; drop that partial first line unless we
        // scanned from the very beginning of the file.
        int start = (scanBytes < fileLength && rawLines.length > 1) ? 1 : 0;
        List<String> lines = new ArrayList<>(Arrays.asList(rawLines).subList(start, rawLines.length));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1); // trailing newline produces a spurious empty element
        }
        if (lines.size() > maxLines) {
            lines = lines.subList(lines.size() - maxLines, lines.size());
        }
        return lines;
    }
}

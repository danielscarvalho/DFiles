package com.dfiles.service;

import com.dfiles.model.FileItem;
import javafx.application.Platform;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Reads a directory's entries off the JavaFX Application Thread. Used both for the
 * initial listing and for re-scans triggered by the folder watcher, so the UI thread
 * never blocks on filesystem I/O.
 */
public class DirectoryScanner {

    private final ExecutorService executor = Executors.newFixedThreadPool(
            2, r -> {
                Thread t = new Thread(r, "dfiles-scanner");
                t.setDaemon(true);
                return t;
            });

    /** onDone/onError are always invoked on the JavaFX Application Thread, regardless of which thread called this. */
    public void scanAsync(Path directory, Consumer<List<FileItem>> onDone, Consumer<Exception> onError) {
        executor.submit(() -> {
            try {
                List<FileItem> items = scan(directory);
                Platform.runLater(() -> onDone.accept(items));
            } catch (Exception e) {
                Platform.runLater(() -> onError.accept(e));
            }
        });
    }

    public List<FileItem> scan(Path directory) throws IOException {
        List<FileItem> items = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) {
                items.add(toFileItem(path));
            }
        }
        return items;
    }

    private FileItem toFileItem(Path path) {
        boolean directory = Files.isDirectory(path);
        long size = 0;
        long lastModified = 0;
        boolean hidden = false;
        try {
            hidden = Files.isHidden(path) || path.getFileName().toString().startsWith(".");
            lastModified = Files.getLastModifiedTime(path).toMillis();
            if (!directory) {
                size = Files.size(path);
            }
        } catch (IOException ignored) {
            // entry may have vanished between listing and stat-ing it; fall back to zeros
        }
        String type = FileTypeUtil.label(path, directory);
        return new FileItem(path.getFileName().toString(), path, directory, size, lastModified, type, hidden);
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}

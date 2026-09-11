package com.dfiles.service;

import javafx.application.Platform;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

/**
 * Watches one directory at a time on a background daemon thread and notifies the UI
 * (via Platform.runLater) whenever entries are created, removed or modified, so the
 * currently open folder stays live without the user hitting refresh.
 *
 * Events are debounced with a short delay since editors/copies fire bursts of events
 * for a single logical change.
 */
public class FolderWatcherService {

    private static final Logger LOGGER = LogManager.getLogger(FolderWatcherService.class);

    private Thread watchThread;
    private volatile WatchService watchService;
    private volatile long lastEventNanos = 0;

    public synchronized void watch(Path directory, Runnable onChange) {
        stop();
        try {
            WatchService ws = FileSystems.getDefault().newWatchService();
            directory.register(ws, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
            this.watchService = ws;
        } catch (IOException e) {
            LOGGER.warn("Could not watch {} for changes", directory, e);
            return; // watching is a nice-to-have; skip if unsupported for this path
        }

        watchThread = new Thread(() -> runLoop(onChange), "dfiles-folder-watcher");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    private void runLoop(Runnable onChange) {
        WatchService ws = this.watchService;
        try {
            while (true) {
                WatchKey key = ws.take();
                boolean relevant = false;
                for (var event : key.pollEvents()) {
                    if (event.kind() == OVERFLOW) continue;
                    relevant = true;
                }
                if (relevant) {
                    scheduleDebouncedNotify(onChange);
                }
                if (!key.reset()) break;
            }
        } catch (InterruptedException | ClosedWatchServiceException ignored) {
            // watcher was stopped, exit quietly
        }
    }

    private void scheduleDebouncedNotify(Runnable onChange) {
        long myStamp = System.nanoTime();
        lastEventNanos = myStamp;
        new Thread(() -> {
            try {
                TimeUnit.MILLISECONDS.sleep(350);
            } catch (InterruptedException ignored) {
                return;
            }
            if (lastEventNanos == myStamp) {
                Platform.runLater(onChange);
            }
        }, "dfiles-watcher-debounce").start();
    }

    public synchronized void stop() {
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {
            }
            watchService = null;
        }
        if (watchThread != null) {
            watchThread.interrupt();
            watchThread = null;
        }
    }
}

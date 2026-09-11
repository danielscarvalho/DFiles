package com.dfiles.service;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Opens a file with the operating system's registered default application for its type.
 *
 * Deliberately avoids java.awt.Desktop: initializing the AWT Toolkit from a thread that
 * already has a running JavaFX/Glass GTK event loop is a well-known source of indefinite
 * hangs on Linux (the two toolkits fight over the native GTK main loop). Shelling out
 * directly to the platform's own "open" command sidesteps AWT entirely.
 */
public final class DesktopOpener {

    private static final Logger LOGGER = LogManager.getLogger(DesktopOpener.class);

    private DesktopOpener() {}

    public static void open(Path path) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase();
        ProcessBuilder pb;
        if (os.contains("win")) {
            pb = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", path.toString());
        } else if (os.contains("mac")) {
            pb = new ProcessBuilder("open", path.toString());
        } else {
            pb = new ProcessBuilder("xdg-open", path.toString());
        }
        LOGGER.info("Opening file with OS default application: {}", path);
        pb.start();
    }
}

package com.dfiles.service;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Opens a folder in Visual Studio Code via its `code` CLI (installed on PATH by VS Code
 * itself on Windows/Linux, or via "Shell Command: Install 'code' command in PATH" on
 * macOS). Falls back to VS Code Insiders, VSCodium, and macOS's `open -a`.
 */
public final class VSCodeLauncher {

    private static final Logger LOGGER = LogManager.getLogger(VSCodeLauncher.class);

    private VSCodeLauncher() {}

    public static boolean open(Path directory) {
        for (String[] command : candidateCommands(directory)) {
            if (tryStart(directory, command)) {
                LOGGER.info("Opened {} in VS Code via: {}", directory, String.join(" ", command));
                return true;
            }
        }
        LOGGER.error("Could not find a VS Code executable to open {}", directory);
        return false;
    }

    private static String[][] candidateCommands(Path directory) {
        String os = System.getProperty("os.name", "").toLowerCase();
        String dir = directory.toString();
        if (os.contains("win")) {
            return new String[][]{
                    {"cmd", "/c", "code", dir},
                    {"cmd", "/c", "code-insiders", dir}
            };
        }
        if (os.contains("mac")) {
            return new String[][]{
                    {"code", dir},
                    {"code-insiders", dir},
                    {"open", "-a", "Visual Studio Code", dir}
            };
        }
        return new String[][]{
                {"code", dir},
                {"code-insiders", dir},
                {"codium", dir}
        };
    }

    private static boolean tryStart(Path directory, String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(directory.toFile());
            pb.start();
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}

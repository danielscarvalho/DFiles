package com.dfiles.service;

import java.io.IOException;
import java.nio.file.Path;

/** Opens a terminal emulator window at a given directory, trying common Linux/macOS/Windows terminals in turn. */
public final class TerminalLauncher {

    private TerminalLauncher() {}

    public static boolean open(Path directory) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return tryStart(directory, "cmd", "/c", "start", "cmd", "/K", "cd", "/d", directory.toString());
        }
        if (os.contains("mac")) {
            return tryStart(directory, "open", "-a", "Terminal", directory.toString());
        }

        String envTerminal = System.getenv("TERMINAL");
        if (envTerminal != null && !envTerminal.isBlank()) {
            if (tryStart(directory, envTerminal, "--working-directory=" + directory)) return true;
        }

        String[][] candidates = {
                {"gnome-terminal", "--working-directory=" + directory},
                {"konsole", "--workdir", directory.toString()},
                {"xfce4-terminal", "--working-directory=" + directory},
                {"mate-terminal", "--working-directory=" + directory},
                {"terminator", "--working-directory=" + directory},
                {"tilix", "--working-directory=" + directory},
                {"xterm", "-e", "bash -c \"cd '" + directory + "' && exec bash\""}
        };
        for (String[] cmd : candidates) {
            if (tryStart(directory, cmd)) return true;
        }
        return false;
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

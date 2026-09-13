package com.dfiles.service;

import javafx.application.Platform;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Runs a bash script from a background thread, streaming its combined stdout/stderr back line
 * by line via the given listener (always invoked on the JavaFX Application Thread). */
public final class ScriptRunner {

    private static final Logger LOGGER = LogManager.getLogger(ScriptRunner.class);

    private ScriptRunner() {}

    public interface Listener {
        void onLine(String line);
        void onFinished(int exitCode);
        void onError(Exception e);
    }

    public static void run(String scriptContent, Path workingDirectory, Listener listener) {
        Thread worker = new Thread(() -> {
            Path tempScript = null;
            try {
                tempScript = Files.createTempFile("dfiles-script-", ".sh");
                Files.writeString(tempScript, scriptContent, StandardCharsets.UTF_8);
                tempScript.toFile().setExecutable(true);

                ProcessBuilder pb = new ProcessBuilder("bash", tempScript.toString());
                pb.directory(workingDirectory.toFile());
                pb.redirectErrorStream(true);
                LOGGER.info("Running script in {}", workingDirectory);
                Process process = pb.start();

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String lineToShow = line;
                        Platform.runLater(() -> listener.onLine(lineToShow));
                    }
                }
                int exitCode = process.waitFor();
                Platform.runLater(() -> listener.onFinished(exitCode));
            } catch (IOException e) {
                LOGGER.error("Script run failed", e);
                Platform.runLater(() -> listener.onError(e));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Platform.runLater(() -> listener.onError(e));
            } finally {
                if (tempScript != null) {
                    try {
                        Files.deleteIfExists(tempScript);
                    } catch (IOException ignored) {
                        // best-effort cleanup of the scratch file
                    }
                }
            }
        }, "dfiles-script-run");
        worker.setDaemon(true);
        worker.start();
    }
}

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
import java.nio.file.Paths;

/**
 * Runs a bash script from a background thread, streaming its combined stdout/stderr back line
 * by line via a {@link Listener} that is always invoked on the JavaFX Application Thread.
 *
 * <p>The script under test is written out to a scratch file first, since {@code bash} needs a
 * real file to execute (there is no "run this string as a script" form of {@link ProcessBuilder}).
 * That scratch file lives under the OS temp directory in a {@code .dfiles} subfolder rather than
 * directly in the temp root, so DFiles' own throwaway files are easy to spot and clean up
 * separately from everything else a user's system drops into {@code /tmp}.
 */
public final class ScriptRunner {

    private static final Logger LOGGER = LogManager.getLogger(ScriptRunner.class);

    private ScriptRunner() {}

    /** Callback for a running script's output and completion, invoked on the FX Application Thread. */
    public interface Listener {
        /** Called once per line of the script's combined stdout/stderr, in order. */
        void onLine(String line);

        /** Called exactly once, after the process exits normally. */
        void onFinished(int exitCode);

        /** Called instead of {@link #onFinished} if the script could not be started or the
         * runner thread was interrupted while waiting on it. */
        void onError(Exception e);
    }

    /**
     * Writes {@code scriptContent} to a scratch file under {@code <tmpdir>/.dfiles/} and runs it
     * with {@code bash}, using {@code workingDirectory} as the process's current directory (so a
     * script under test can act on whatever folder the user has open). The scratch file is
     * deleted again once the process exits, regardless of outcome.
     *
     * @param scriptContent   the full text of the script to run
     * @param workingDirectory the directory the script should be run in
     * @param listener        receives output lines and the final result, on the FX thread
     */
    public static void run(String scriptContent, Path workingDirectory, Listener listener) {
        Thread worker = new Thread(() -> {
            Path tempScript = null;
            try {
                Path scratchDir = Paths.get(System.getProperty("java.io.tmpdir"), ".dfiles");
                Files.createDirectories(scratchDir);
                tempScript = Files.createTempFile(scratchDir, "dfiles-script-", ".sh");
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

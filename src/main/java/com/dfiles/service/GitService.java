package com.dfiles.service;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Thin wrapper that shells out to the `git` command line for the folder currently being browsed. */
public class GitService {

    private static final Logger LOGGER = LogManager.getLogger(GitService.class);

    public static class GitResult {
        public final boolean success;
        public final String output;

        public GitResult(boolean success, String output) {
            this.success = success;
            this.output = output;
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dfiles-git");
        t.setDaemon(true);
        return t;
    });

    public boolean isGitRepo(Path directory) {
        Path dir = directory;
        while (dir != null) {
            if (Files.exists(dir.resolve(".git"))) return true;
            dir = dir.getParent();
        }
        return false;
    }

    public void runAsync(Path directory, Consumer<GitResult> callback, String... args) {
        executor.submit(() -> {
            GitResult result = run(directory, args);
            javafx.application.Platform.runLater(() -> callback.accept(result));
        });
    }

    public GitResult run(Path directory, String... args) {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        LOGGER.info("Running: git {} (in {})", String.join(" ", args), directory);
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(directory.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            int exit = process.waitFor();
            if (exit != 0) {
                LOGGER.warn("git {} exited with code {}: {}", String.join(" ", args), exit, sb);
            }
            return new GitResult(exit == 0, sb.toString());
        } catch (IOException | InterruptedException e) {
            LOGGER.error("Failed to run git {}", String.join(" ", args), e);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new GitResult(false, e.getMessage());
        }
    }

    public void status(Path dir, Consumer<GitResult> cb) { runAsync(dir, cb, "status", "--short", "--branch"); }
    public void pull(Path dir, Consumer<GitResult> cb) { runAsync(dir, cb, "pull"); }
    public void push(Path dir, Consumer<GitResult> cb) { runAsync(dir, cb, "push"); }
    public void init(Path dir, Consumer<GitResult> cb) { runAsync(dir, cb, "init"); }
    public void log(Path dir, Consumer<GitResult> cb) { runAsync(dir, cb, "log", "--oneline", "-20"); }

    public void commitAll(Path dir, String message, Consumer<GitResult> cb) {
        executor.submit(() -> {
            GitResult add = run(dir, "add", "-A");
            if (!add.success) {
                javafx.application.Platform.runLater(() -> cb.accept(add));
                return;
            }
            GitResult commit = run(dir, "commit", "-m", message);
            javafx.application.Platform.runLater(() -> cb.accept(commit));
        });
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}

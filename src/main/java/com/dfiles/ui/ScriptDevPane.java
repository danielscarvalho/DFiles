package com.dfiles.ui;

import com.dfiles.i18n.I18n;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * Tab 2 content: an AI-assisted bash script workspace split into three mouse-resizable rows —
 * a prompt sent to an LLM, the resulting (or hand-edited) script, and a terminal-style view of
 * running it.
 */
public class ScriptDevPane {

    private final VBox root = new VBox();
    private final Label scriptNameLabel = new Label();
    private final TextArea promptArea = new TextArea();
    private final TextArea scriptArea = new TextArea();
    private final TextArea terminalArea = new TextArea();
    private final Button runPromptButton = new Button();
    private final Button runScriptButton = new Button();
    private final Button saveButton = new Button();
    private final Label promptRowLabel = new Label();
    private final Label scriptRowLabel = new Label();
    private final Label terminalRowLabel = new Label();
    private final Label noScriptLabel = new Label();

    private Consumer<String> onRunPrompt;
    private Runnable onRunScript;
    private Runnable onSave;

    private String currentScriptName;

    public ScriptDevPane() {
        promptArea.setWrapText(true);

        scriptArea.setWrapText(false);
        scriptArea.getStyleClass().add("script-editor");

        terminalArea.setEditable(false);
        terminalArea.setWrapText(false);
        terminalArea.getStyleClass().add("terminal-output");

        runPromptButton.setOnAction(e -> {
            if (onRunPrompt != null && !promptArea.getText().isBlank()) onRunPrompt.accept(promptArea.getText());
        });
        runScriptButton.setOnAction(e -> {
            if (onRunScript != null) onRunScript.run();
        });
        saveButton.setOnAction(e -> {
            if (onSave != null) onSave.run();
        });
        runScriptButton.setDisable(true);
        saveButton.setDisable(true);

        noScriptLabel.getStyleClass().add("status-dim");

        HBox promptHeader = new HBox(8, promptRowLabel, spacer(), runPromptButton);
        promptHeader.setAlignment(Pos.CENTER_LEFT);
        VBox promptRow = new VBox(4, promptHeader, promptArea);
        promptRow.setPadding(new Insets(6));
        VBox.setVgrow(promptArea, Priority.ALWAYS);

        HBox scriptHeader = new HBox(8, scriptRowLabel, scriptNameLabel, noScriptLabel, spacer(), saveButton);
        scriptHeader.setAlignment(Pos.CENTER_LEFT);
        VBox scriptRow = new VBox(4, scriptHeader, scriptArea);
        scriptRow.setPadding(new Insets(6));
        VBox.setVgrow(scriptArea, Priority.ALWAYS);

        HBox terminalHeader = new HBox(8, terminalRowLabel, spacer(), runScriptButton);
        terminalHeader.setAlignment(Pos.CENTER_LEFT);
        VBox terminalRow = new VBox(4, terminalHeader, terminalArea);
        terminalRow.setPadding(new Insets(6));
        VBox.setVgrow(terminalArea, Priority.ALWAYS);

        SplitPane split = new SplitPane(promptRow, scriptRow, terminalRow);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.33, 0.66);
        VBox.setVgrow(split, Priority.ALWAYS);

        root.getChildren().add(split);
        applyLabels();
    }

    private Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    public void applyLabels() {
        promptRowLabel.setText(I18n.t("scriptdev.promptLabel"));
        scriptRowLabel.setText(I18n.t("scriptdev.scriptLabel") + (currentScriptName == null ? "" : ":"));
        terminalRowLabel.setText(I18n.t("scriptdev.terminalLabel"));
        runPromptButton.setText(I18n.t("scriptdev.runPrompt"));
        runScriptButton.setText(I18n.t("scriptdev.runScript"));
        saveButton.setText(I18n.t("scriptdev.save"));
        promptArea.setPromptText(I18n.t("scriptdev.promptPlaceholder"));
        noScriptLabel.setText(currentScriptName == null ? I18n.t("scripts.noneSelected") : "");
    }

    public void loadScript(String name, String content) {
        this.currentScriptName = name;
        scriptNameLabel.setText(name);
        scriptArea.setText(content);
        terminalArea.clear();
        runScriptButton.setDisable(false);
        saveButton.setDisable(false);
        applyLabels();
    }

    public void clearScript() {
        this.currentScriptName = null;
        scriptNameLabel.setText("");
        scriptArea.clear();
        terminalArea.clear();
        runScriptButton.setDisable(true);
        saveButton.setDisable(true);
        applyLabels();
    }

    public String getCurrentScriptName() { return currentScriptName; }
    public String getScriptContent() { return scriptArea.getText(); }
    public void setScriptContent(String content) { scriptArea.setText(content); }
    public void appendTerminalLine(String line) { terminalArea.appendText(line + "\n"); }
    public void clearTerminal() { terminalArea.clear(); }
    public void setPromptBusy(boolean busy) { runPromptButton.setDisable(busy); }
    public void setScriptRunning(boolean running) { runScriptButton.setDisable(running); }

    public VBox getNode() { return root; }
    public void setOnRunPrompt(Consumer<String> onRunPrompt) { this.onRunPrompt = onRunPrompt; }
    public void setOnRunScript(Runnable onRunScript) { this.onRunScript = onRunScript; }
    public void setOnSave(Runnable onSave) { this.onSave = onSave; }
}

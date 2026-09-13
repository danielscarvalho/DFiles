package com.dfiles.ui;

import com.dfiles.i18n.I18n;
import com.dfiles.service.AiProvider;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.function.Consumer;

/**
 * Tab 2 content: an AI-assisted bash script workspace split into three mouse-resizable rows —
 * a prompt sent to an LLM (with a choice of AI backend), the resulting or hand-edited script,
 * and a terminal-style view of running it.
 *
 * <p>This class only builds and exposes the view; it owns no persistence or process-running
 * logic itself. {@link com.dfiles.ui.MainController} wires its callbacks to
 * {@link com.dfiles.service.AiScriptService}, {@link com.dfiles.service.ScriptRunner} and the
 * {@link com.dfiles.db.Database}.
 */
public class ScriptDevPane {

    private final VBox root = new VBox();
    private final Label scriptNameLabel = new Label();
    private final Label scriptHashLabel = new Label();
    private final TextArea promptArea = new TextArea();
    private final TextArea scriptArea = new TextArea();
    private final TextArea terminalArea = new TextArea();
    private final Button runPromptButton = new Button();
    private final Button runScriptButton = new Button();
    private final Button saveButton = new Button();
    private final ComboBox<AiProvider> providerCombo = new ComboBox<>();
    private final Label promptRowLabel = new Label();
    private final Label scriptRowLabel = new Label();
    private final Label terminalRowLabel = new Label();
    private final Label noScriptLabel = new Label();

    private Consumer<String> onRunPrompt;
    private Runnable onRunScript;
    private Runnable onSave;
    private Consumer<AiProvider> onProviderChange;

    private String currentScriptName;

    public ScriptDevPane() {
        promptArea.setWrapText(true);

        scriptArea.setWrapText(false);
        scriptArea.getStyleClass().add("script-editor");

        terminalArea.setEditable(false);
        terminalArea.setWrapText(false);
        terminalArea.getStyleClass().add("terminal-output");

        providerCombo.setItems(FXCollections.observableArrayList(AiProvider.values()));
        providerCombo.setValue(AiProvider.OPENAI);
        providerCombo.setConverter(new StringConverter<>() {
            @Override public String toString(AiProvider p) { return p == null ? "" : p.getDisplayName(); }
            @Override public AiProvider fromString(String s) { return providerCombo.getValue(); }
        });
        providerCombo.setOnAction(e -> {
            if (onProviderChange != null) onProviderChange.accept(providerCombo.getValue());
        });

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
        scriptHashLabel.getStyleClass().add("status-dim");

        HBox promptHeader = new HBox(8, promptRowLabel, spacer(), providerCombo, runPromptButton);
        promptHeader.setAlignment(Pos.CENTER_LEFT);
        VBox promptRow = new VBox(4, promptHeader, promptArea);
        promptRow.setPadding(new Insets(6));
        VBox.setVgrow(promptArea, Priority.ALWAYS);

        HBox scriptHeader = new HBox(8, scriptRowLabel, scriptNameLabel, scriptHashLabel, noScriptLabel, spacer(), saveButton);
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

    /** Re-reads every label, button and placeholder from {@link I18n} — called once at startup
     * and again whenever the user switches language. */
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

    /** Populates all three rows from a script's saved state and enables the Save/Run Script
     * actions, which are disabled until a script has been loaded or created. */
    public void loadScript(String name, String prompt, String content, String lastOutput, String hash) {
        this.currentScriptName = name;
        scriptNameLabel.setText(name);
        promptArea.setText(prompt == null ? "" : prompt);
        scriptArea.setText(content == null ? "" : content);
        terminalArea.setText(lastOutput == null ? "" : lastOutput);
        setScriptHash(hash);
        runScriptButton.setDisable(false);
        saveButton.setDisable(false);
        applyLabels();
    }

    /** Resets the pane to its empty, no-script-loaded state (e.g. after the loaded script is deleted). */
    public void clearScript() {
        this.currentScriptName = null;
        scriptNameLabel.setText("");
        promptArea.clear();
        scriptArea.clear();
        terminalArea.clear();
        setScriptHash(null);
        runScriptButton.setDisable(true);
        saveButton.setDisable(true);
        applyLabels();
    }

    /** Shows a short prefix of the script's content hash next to its name (full hash in the
     * tooltip), or clears the label if {@code hash} is null/blank — used both when a script is
     * loaded and after a Save recomputes the hash for the just-edited content. */
    public void setScriptHash(String hash) {
        if (hash == null || hash.isBlank()) {
            scriptHashLabel.setText("");
            scriptHashLabel.setTooltip(null);
            return;
        }
        scriptHashLabel.setText("#" + hash.substring(0, Math.min(10, hash.length())));
        scriptHashLabel.setTooltip(new Tooltip(hash));
    }

    /** Selects which AI backend the provider combo box shows, without firing the change callback
     * (used when restoring the persisted choice at startup). */
    public void setProvider(AiProvider provider) {
        providerCombo.setValue(provider);
    }

    public AiProvider getProvider() { return providerCombo.getValue(); }
    public String getCurrentScriptName() { return currentScriptName; }
    public String getPromptText() { return promptArea.getText(); }
    public String getScriptContent() { return scriptArea.getText(); }
    public void setScriptContent(String content) { scriptArea.setText(content); }
    public void appendTerminalLine(String line) { terminalArea.appendText(line + "\n"); }
    public void clearTerminal() { terminalArea.clear(); }
    public String getTerminalText() { return terminalArea.getText(); }
    public void setPromptBusy(boolean busy) { runPromptButton.setDisable(busy); }
    public void setScriptRunning(boolean running) { runScriptButton.setDisable(running); }

    public VBox getNode() { return root; }
    public void setOnRunPrompt(Consumer<String> onRunPrompt) { this.onRunPrompt = onRunPrompt; }
    public void setOnRunScript(Runnable onRunScript) { this.onRunScript = onRunScript; }
    public void setOnSave(Runnable onSave) { this.onSave = onSave; }
    public void setOnProviderChange(Consumer<AiProvider> onProviderChange) { this.onProviderChange = onProviderChange; }
}

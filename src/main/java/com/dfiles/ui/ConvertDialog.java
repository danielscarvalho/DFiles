package com.dfiles.ui;

import com.dfiles.i18n.I18n;
import com.dfiles.model.TableData;
import com.dfiles.service.TableConverter;
import com.dfiles.service.TableInputFormat;
import com.dfiles.service.TableOutputFormat;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The "Convert" dialog: turns pasted text, a local file, or a downloaded URL — in CSV, TSV, JSON,
 * Markdown-table, YAML, or Excel (.xlsx) format — into SQL or CSV, inspired by
 * <a href="https://convertcsv.com/csv-to-sql.htm">convertcsv.com</a>. Every input format can
 * produce every output format, since both sides go through the common {@link TableData}
 * intermediate form in {@link TableConverter}.
 *
 * <p>The source (pasted text, chosen file, or downloaded bytes) is only ever read, never
 * modified: results are written to a brand-new file chosen via {@link FileChooser}, and choosing
 * the exact source file as the save target is refused.
 */
public final class ConvertDialog {

    private static final Logger LOGGER = LogManager.getLogger(ConvertDialog.class);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private static final Pattern NON_IDENTIFIER = Pattern.compile("[^A-Za-z0-9_]+");
    private static final Pattern URL_LAST_SEGMENT = Pattern.compile("/([^/?#]+)[^/]*$");

    private ConvertDialog() {}

    private record NamedCharset(String label, Charset charset) {
        @Override public String toString() { return label; }
    }

    private static final List<NamedCharset> CHARSETS = List.of(
            new NamedCharset("UTF-8", StandardCharsets.UTF_8),
            new NamedCharset("UTF-16", StandardCharsets.UTF_16),
            new NamedCharset("ISO-8859-1 (Latin-1)", StandardCharsets.ISO_8859_1),
            new NamedCharset("US-ASCII", StandardCharsets.US_ASCII),
            new NamedCharset("Windows-1252", Charset.forName("windows-1252"))
    );

    /** Opens the dialog with nothing pre-filled — the user picks a source from scratch. */
    public static void show(Stage owner) {
        show(owner, null);
    }

    /** Opens the dialog with the Local File tab already selected and {@code prefillFile} loaded
     * into it, for the file table's "Convert…" context menu item. */
    public static void show(Stage owner, Path prefillFile) {
        TextArea pasteArea = new TextArea();
        pasteArea.setWrapText(false);
        pasteArea.setPromptText(I18n.t("dialog.convert.pastePlaceholder"));
        Tab pasteTab = new Tab(I18n.t("dialog.convert.tabPaste"), pasteArea);
        pasteTab.setClosable(false);

        TextField fileField = new TextField();
        fileField.setEditable(false);
        fileField.setPromptText(I18n.t("dialog.convert.noFileChosen"));
        Button browseButton = new Button(I18n.t("dialog.convert.browse"));
        HBox fileRow = new HBox(8, fileField, browseButton);
        HBox.setHgrow(fileField, Priority.ALWAYS);
        VBox fileTabContent = new VBox(10, fileRow);
        fileTabContent.setPadding(new Insets(12));
        Tab fileTab = new Tab(I18n.t("dialog.convert.tabFile"), fileTabContent);
        fileTab.setClosable(false);

        TextField urlField = new TextField();
        urlField.setPromptText(I18n.t("dialog.convert.urlPlaceholder"));
        VBox urlTabContent = new VBox(10, urlField);
        urlTabContent.setPadding(new Insets(12));
        Tab urlTab = new Tab(I18n.t("dialog.convert.tabUrl"), urlTabContent);
        urlTab.setClosable(false);

        TabPane sourceTabs = new TabPane(pasteTab, fileTab, urlTab);
        sourceTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        java.util.concurrent.atomic.AtomicReference<Path> chosenFile = new java.util.concurrent.atomic.AtomicReference<>();
        browseButton.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.t("dialog.convert.tabFile"));
            chooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter(I18n.t("dialog.convert.filterSupported"),
                            "*.csv", "*.tsv", "*.json", "*.md", "*.markdown", "*.yaml", "*.yml", "*.xlsx"),
                    new FileChooser.ExtensionFilter(I18n.t("dialog.convert.filterAll"), "*.*"));
            java.io.File file = chooser.showOpenDialog(owner);
            if (file == null) return;
            chosenFile.set(file.toPath());
            fileField.setText(file.getAbsolutePath());
        });

        ComboBox<NamedCharset> encodingCombo = new ComboBox<>(FXCollections.observableArrayList(CHARSETS));
        encodingCombo.setValue(CHARSETS.get(0));

        ComboBox<TableInputFormat> inputFormatCombo = new ComboBox<>(FXCollections.observableArrayList(TableInputFormat.values()));
        inputFormatCombo.setValue(TableInputFormat.AUTO);

        ComboBox<TableOutputFormat> outputFormatCombo = new ComboBox<>(FXCollections.observableArrayList(TableOutputFormat.values()));
        outputFormatCombo.setValue(TableOutputFormat.SQL);

        TextField tableNameField = new TextField("converted_table");
        boolean[] tableNameEdited = {false};
        tableNameField.textProperty().addListener((obs, old, val) -> tableNameEdited[0] = true);
        Label tableNameLabel = new Label(I18n.t("dialog.convert.tableName"));
        javafx.scene.control.CheckBox includeCreateTableCheck = new javafx.scene.control.CheckBox(I18n.t("dialog.convert.includeCreateTable"));
        includeCreateTableCheck.setSelected(true);
        Runnable syncTableNameVisibility = () -> {
            boolean needsName = outputFormatCombo.getValue() == TableOutputFormat.SQL;
            tableNameLabel.setVisible(needsName);
            tableNameLabel.setManaged(needsName);
            tableNameField.setVisible(needsName);
            tableNameField.setManaged(needsName);
            includeCreateTableCheck.setVisible(needsName);
            includeCreateTableCheck.setManaged(needsName);
        };
        outputFormatCombo.valueProperty().addListener((obs, old, val) -> syncTableNameVisibility.run());
        syncTableNameVisibility.run();

        Runnable autoFillTableName = () -> {
            if (tableNameEdited[0]) return;
            String hint = null;
            if (sourceTabs.getSelectionModel().getSelectedItem() == fileTab && chosenFile.get() != null) {
                hint = baseName(chosenFile.get().getFileName().toString());
            } else if (sourceTabs.getSelectionModel().getSelectedItem() == urlTab && !urlField.getText().isBlank()) {
                hint = baseName(lastUrlSegment(urlField.getText()));
            }
            if (hint != null && !hint.isBlank()) {
                tableNameEdited[0] = false; // setText below fires the listener; restore after
                tableNameField.setText(sanitizeForDefault(hint));
                tableNameEdited[0] = false;
            }
        };
        fileField.textProperty().addListener((obs, old, val) -> autoFillTableName.run());
        urlField.textProperty().addListener((obs, old, val) -> autoFillTableName.run());

        if (prefillFile != null) {
            sourceTabs.getSelectionModel().select(fileTab);
            chosenFile.set(prefillFile);
            fileField.setText(prefillFile.toAbsolutePath().toString());
        }

        GridPane optionsGrid = new GridPane();
        optionsGrid.setHgap(10);
        optionsGrid.setVgap(8);
        optionsGrid.setPadding(new Insets(10, 0, 10, 0));
        optionsGrid.addRow(0, new Label(I18n.t("dialog.convert.encoding")), encodingCombo,
                new Label(I18n.t("dialog.convert.inputFormat")), inputFormatCombo);
        optionsGrid.addRow(1, new Label(I18n.t("dialog.convert.outputFormat")), outputFormatCombo,
                tableNameLabel, tableNameField);
        optionsGrid.add(includeCreateTableCheck, 3, 2);
        for (int i = 0; i < 4; i++) {
            ColumnConstraints cc = new ColumnConstraints();
            if (i % 2 == 1) { cc.setHgrow(Priority.ALWAYS); }
            optionsGrid.getColumnConstraints().add(cc);
        }

        TextArea previewArea = new TextArea();
        previewArea.setEditable(false);
        previewArea.setWrapText(false);
        previewArea.getStyleClass().add("git-output");
        previewArea.setPromptText(I18n.t("dialog.convert.previewPlaceholder"));
        VBox.setVgrow(previewArea, Priority.ALWAYS);

        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: #c0392b;");
        errorLabel.setWrapText(true);

        Button convertButton = new Button(I18n.t("dialog.convert.convert"));
        Button saveAsButton = new Button(I18n.t("dialog.convert.saveAs"));
        Button copyButton = new Button(I18n.t("dialog.convert.copy"));
        saveAsButton.setDisable(true);
        copyButton.setDisable(true);

        java.util.concurrent.atomic.AtomicReference<String> lastResult = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Path> lastSourcePath = new java.util.concurrent.atomic.AtomicReference<>();

        convertButton.setOnAction(e -> {
            errorLabel.setText("");
            saveAsButton.setDisable(true);
            copyButton.setDisable(true);
            Charset charset = encodingCombo.getValue().charset();
            TableInputFormat requestedFormat = inputFormatCombo.getValue();
            TableOutputFormat outputFormat = outputFormatCombo.getValue();
            String tableName = tableNameField.getText();
            boolean includeCreateTable = includeCreateTableCheck.isSelected();
            Tab activeTab = sourceTabs.getSelectionModel().getSelectedItem();

            convertButton.setDisable(true);
            Thread worker = new Thread(() -> {
                try {
                    byte[] rawBytes;
                    String fileNameHint;
                    Path sourcePath = null;
                    if (activeTab == pasteTab) {
                        String text = pasteArea.getText();
                        if (text == null || text.isBlank()) {
                            throw new IOException(I18n.t("error.convert.noInput"));
                        }
                        rawBytes = text.getBytes(charset);
                        fileNameHint = null;
                    } else if (activeTab == fileTab) {
                        Path path = chosenFile.get();
                        if (path == null) throw new IOException(I18n.t("error.convert.noInput"));
                        rawBytes = Files.readAllBytes(path);
                        fileNameHint = path.getFileName().toString();
                        sourcePath = path;
                    } else {
                        String url = urlField.getText();
                        if (url == null || url.isBlank()) throw new IOException(I18n.t("error.convert.noInput"));
                        rawBytes = download(url);
                        fileNameHint = lastUrlSegment(url);
                    }

                    TableInputFormat resolvedFormat = requestedFormat == TableInputFormat.AUTO
                            ? TableConverter.detect(rawBytes, fileNameHint, charset)
                            : requestedFormat;
                    TableData table = TableConverter.parse(rawBytes, charset, resolvedFormat);
                    String output = TableConverter.write(table, outputFormat, tableName, includeCreateTable);

                    Path finalSourcePath = sourcePath;
                    Platform.runLater(() -> {
                        previewArea.setText(output);
                        lastResult.set(output);
                        lastSourcePath.set(finalSourcePath);
                        saveAsButton.setDisable(false);
                        copyButton.setDisable(false);
                        convertButton.setDisable(false);
                    });
                } catch (IOException | InterruptedException ex) {
                    LOGGER.warn("Convert failed", ex);
                    Platform.runLater(() -> {
                        errorLabel.setText(ex.getMessage());
                        convertButton.setDisable(false);
                    });
                }
            }, "dfiles-convert");
            worker.setDaemon(true);
            worker.start();
        });

        copyButton.setOnAction(e -> {
            if (lastResult.get() == null) return;
            ClipboardContent content = new ClipboardContent();
            content.putString(lastResult.get());
            Clipboard.getSystemClipboard().setContent(content);
        });

        saveAsButton.setOnAction(e -> {
            if (lastResult.get() == null) return;
            TableOutputFormat outputFormat = outputFormatCombo.getValue();
            String extension = outputFormat == TableOutputFormat.SQL ? "sql" : "csv";
            FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.t("dialog.convert.saveAs"));
            String suggested = (tableNameField.getText().isBlank() ? "converted" : tableNameField.getText()) + "." + extension;
            chooser.setInitialFileName(suggested);
            if (lastSourcePath.get() != null && lastSourcePath.get().getParent() != null) {
                chooser.setInitialDirectory(lastSourcePath.get().getParent().toFile());
            }
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(extension.toUpperCase(), "*." + extension));
            java.io.File target = chooser.showSaveDialog(owner);
            if (target == null) return;
            Path targetPath = target.toPath();
            if (lastSourcePath.get() != null && isSamePath(targetPath, lastSourcePath.get())) {
                errorLabel.setText(I18n.t("error.convert.sameAsSource"));
                return;
            }
            try {
                Files.writeString(targetPath, lastResult.get(), StandardCharsets.UTF_8);
                errorLabel.setStyle("-fx-text-fill: #2e7d32;");
                errorLabel.setText(I18n.t("status.convert.saved", targetPath.getFileName().toString()));
            } catch (IOException ex) {
                LOGGER.error("Could not save converted output to {}", targetPath, ex);
                errorLabel.setStyle("-fx-text-fill: #c0392b;");
                errorLabel.setText(I18n.t("error.convert.save", ex.getMessage()));
            }
        });

        HBox buttonBar = new HBox(10, convertButton, copyButton, saveAsButton, spacer(), errorLabel);
        buttonBar.setAlignment(Pos.CENTER_LEFT);
        buttonBar.setPadding(new Insets(4, 0, 0, 0));

        VBox centerBox = new VBox(8, sourceTabs, optionsGrid, buttonBar, previewArea);
        centerBox.setPadding(new Insets(14));
        VBox.setVgrow(sourceTabs, Priority.SOMETIMES);
        VBox.setVgrow(previewArea, Priority.ALWAYS);

        BorderPane dialogRoot = new BorderPane();
        dialogRoot.setCenter(centerBox);

        Stage dialogStage = new Stage();
        Scene scene = new Scene(dialogRoot, 760, 640);
        scene.getStylesheets().add(ConvertDialog.class.getResource("/css/dfiles.css").toExternalForm());
        dialogStage.setScene(scene);
        dialogStage.setTitle(I18n.t("dialog.convert.title"));
        dialogStage.initOwner(owner);
        dialogStage.initModality(Modality.WINDOW_MODAL);
        dialogStage.show();
    }

    private static byte[] download(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<byte[]> response;
        try {
            response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException | IllegalArgumentException e) {
            throw new IOException(I18n.t("error.convert.network", e.getMessage()), e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException(I18n.t("error.convert.network", "HTTP " + response.statusCode()));
        }
        return response.body();
    }

    private static boolean isSamePath(Path a, Path b) {
        try {
            return Files.exists(a) && Files.exists(b) && Files.isSameFile(a, b);
        } catch (IOException e) {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        }
    }

    private static String lastUrlSegment(String url) {
        var m = URL_LAST_SEGMENT.matcher(url);
        return m.find() ? m.group(1) : "download";
    }

    private static String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String sanitizeForDefault(String name) {
        String cleaned = NON_IDENTIFIER.matcher(name).replaceAll("_");
        return cleaned.isBlank() ? "converted_table" : cleaned;
    }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }
}

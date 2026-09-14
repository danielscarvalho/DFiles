package com.dfiles.ui;

import com.dfiles.i18n.I18n;
import com.dfiles.model.TableData;
import com.dfiles.service.SqlDialect;
import com.dfiles.service.TableConverter;
import com.dfiles.service.TableInputFormat;
import com.dfiles.service.TableOutputFormat;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
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
import javafx.stage.Screen;
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
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * The "Convert" dialog: turns pasted text, a local file, or a URL — in CSV, TSV, JSON, XML,
 * Markdown-table, YAML, or Excel (.xlsx) format — into SQL (for a choice of SQLite, MySQL,
 * PostgreSQL, Oracle, or Microsoft SQL Server), CSV, TSV, JSON, XML, an HTML table, a Markdown
 * table, YAML, or Excel, inspired by
 * <a href="https://convertcsv.com/csv-to-sql.htm">convertcsv.com</a>. Every input format can
 * produce every output format, since both sides go through the common {@link TableData}
 * intermediate form in {@link TableConverter}.
 *
 * <p>A single source field takes either a local file path or a URL; leaving it blank means the
 * big text area below it is the input to convert (pasted directly). Filling it in instead turns
 * that same text area into a read-only head-sample preview of the file/URL's content, fetched
 * automatically when the field loses focus, Enter is pressed in it, or a file is chosen via
 * Browse — Convert itself always re-reads the full source fresh rather than reusing the sample.
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
    private static final Pattern URL_PREFIX = Pattern.compile("^https?://", Pattern.CASE_INSENSITIVE);
    private static final int PREVIEW_MAX_LINES = 60;

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

    /** Opens the dialog with {@code prefillFile}'s path already in the source field (and its
     * preview already loading), for the file table's "Convert…" context menu item. */
    public static void show(Stage owner, Path prefillFile) {
        TextField sourceField = new TextField();
        sourceField.setPromptText(I18n.t("dialog.convert.sourcePlaceholder"));
        Button browseButton = new Button(I18n.t("dialog.convert.browse"));
        HBox sourceRow = new HBox(8, sourceField, browseButton);
        HBox.setHgrow(sourceField, Priority.ALWAYS);

        TextArea pasteArea = new TextArea();
        pasteArea.setWrapText(false);
        pasteArea.setPromptText(I18n.t("dialog.convert.pastePlaceholder"));
        VBox.setVgrow(pasteArea, Priority.ALWAYS);

        ComboBox<NamedCharset> encodingCombo = new ComboBox<>(FXCollections.observableArrayList(CHARSETS));
        encodingCombo.setValue(CHARSETS.get(0));

        // Tracks which source string the paste area currently shows a preview of, so unrelated
        // events (e.g. re-focusing the field without changing it) don't refetch needlessly, and
        // so we know whether the area holds a preview (not to be treated as pasted input) or the
        // user's own text.
        AtomicReference<String> previewedSource = new AtomicReference<>("");
        Runnable loadPreview = () -> {
            String sourceText = sourceField.getText();
            if (sourceText == null || sourceText.isBlank()) {
                if (!previewedSource.get().isEmpty()) pasteArea.clear();
                previewedSource.set("");
                pasteArea.setEditable(true);
                return;
            }
            if (sourceText.equals(previewedSource.get())) return;
            previewedSource.set(sourceText);
            Charset charset = encodingCombo.getValue().charset();
            pasteArea.setEditable(false);
            pasteArea.setText(I18n.t("dialog.convert.loadingPreview"));
            Thread worker = new Thread(() -> {
                try {
                    byte[] bytes;
                    String fileNameHint;
                    if (isUrl(sourceText)) {
                        bytes = download(sourceText);
                        fileNameHint = lastUrlSegment(sourceText);
                    } else {
                        Path path = Path.of(sourceText);
                        if (!Files.exists(path)) throw new IOException(I18n.t("error.convert.fileNotFound", sourceText));
                        bytes = Files.readAllBytes(path);
                        fileNameHint = path.getFileName().toString();
                    }
                    TableInputFormat detected = TableConverter.detect(bytes, fileNameHint, charset);
                    String sample = detected == TableInputFormat.XLSX
                            ? I18n.t("dialog.convert.binaryPreviewPlaceholder", bytes.length)
                            : headSample(new String(bytes, charset));
                    Platform.runLater(() -> pasteArea.setText(sample));
                } catch (IOException | InterruptedException | InvalidPathException ex) {
                    LOGGER.warn("Could not load preview for {}", sourceText, ex);
                    Platform.runLater(() -> pasteArea.setText(I18n.t("error.convert.previewFailed", ex.getMessage())));
                }
            }, "dfiles-convert-preview");
            worker.setDaemon(true);
            worker.start();
        };
        sourceField.setOnAction(e -> loadPreview.run());
        sourceField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) loadPreview.run();
        });

        browseButton.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.t("dialog.convert.source"));
            chooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter(I18n.t("dialog.convert.filterSupported"),
                            "*.csv", "*.tsv", "*.json", "*.xml", "*.md", "*.markdown", "*.yaml", "*.yml", "*.xlsx"),
                    new FileChooser.ExtensionFilter(I18n.t("dialog.convert.filterAll"), "*.*"));
            java.io.File file = chooser.showOpenDialog(owner);
            if (file == null) return;
            sourceField.setText(file.getAbsolutePath());
            loadPreview.run();
        });

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
        Label dialectLabel = new Label(I18n.t("dialog.convert.sqlDialect"));
        ComboBox<SqlDialect> dialectCombo = new ComboBox<>(FXCollections.observableArrayList(SqlDialect.values()));
        dialectCombo.setValue(SqlDialect.SQLITE);
        Runnable syncSqlOptionsVisibility = () -> {
            boolean isSql = outputFormatCombo.getValue() == TableOutputFormat.SQL;
            for (javafx.scene.Node n : List.of(tableNameLabel, tableNameField, includeCreateTableCheck, dialectLabel, dialectCombo)) {
                n.setVisible(isSql);
                n.setManaged(isSql);
            }
        };
        outputFormatCombo.valueProperty().addListener((obs, old, val) -> syncSqlOptionsVisibility.run());
        syncSqlOptionsVisibility.run();

        Runnable autoFillTableName = () -> {
            if (tableNameEdited[0]) return;
            String sourceText = sourceField.getText();
            String hint = null;
            if (sourceText != null && !sourceText.isBlank()) {
                hint = isUrl(sourceText) ? baseName(lastUrlSegment(sourceText)) : baseName(fileNameOf(sourceText));
            }
            if (hint != null && !hint.isBlank()) {
                tableNameEdited[0] = false; // setText below fires the listener; restore after
                tableNameField.setText(sanitizeForDefault(hint));
                tableNameEdited[0] = false;
            }
        };
        sourceField.textProperty().addListener((obs, old, val) -> autoFillTableName.run());

        if (prefillFile != null) {
            sourceField.setText(prefillFile.toAbsolutePath().toString());
            loadPreview.run();
        }

        GridPane optionsGrid = new GridPane();
        optionsGrid.setHgap(10);
        optionsGrid.setVgap(8);
        optionsGrid.setPadding(new Insets(10, 0, 10, 0));
        optionsGrid.addRow(0, new Label(I18n.t("dialog.convert.encoding")), encodingCombo,
                new Label(I18n.t("dialog.convert.inputFormat")), inputFormatCombo);
        optionsGrid.addRow(1, new Label(I18n.t("dialog.convert.outputFormat")), outputFormatCombo,
                tableNameLabel, tableNameField);
        optionsGrid.addRow(2, dialectLabel, dialectCombo, includeCreateTableCheck);
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

        // Exactly one of these is populated after a successful conversion, depending on whether
        // the chosen output format is text or the one binary format (XLSX).
        AtomicReference<String> lastTextResult = new AtomicReference<>();
        AtomicReference<byte[]> lastBytesResult = new AtomicReference<>();
        AtomicReference<Path> lastSourcePath = new AtomicReference<>();

        convertButton.setOnAction(e -> {
            errorLabel.setText("");
            saveAsButton.setDisable(true);
            copyButton.setDisable(true);
            Charset charset = encodingCombo.getValue().charset();
            TableInputFormat requestedFormat = inputFormatCombo.getValue();
            TableOutputFormat outputFormat = outputFormatCombo.getValue();
            String tableName = tableNameField.getText();
            boolean includeCreateTable = includeCreateTableCheck.isSelected();
            SqlDialect dialect = dialectCombo.getValue();
            String sourceText = sourceField.getText();
            String pastedText = pasteArea.getText();
            boolean hasSource = sourceText != null && !sourceText.isBlank();

            convertButton.setDisable(true);
            Thread worker = new Thread(() -> {
                try {
                    byte[] rawBytes;
                    String fileNameHint;
                    Path sourcePath = null;
                    if (!hasSource) {
                        if (pastedText == null || pastedText.isBlank()) {
                            throw new IOException(I18n.t("error.convert.noInput"));
                        }
                        rawBytes = pastedText.getBytes(charset);
                        fileNameHint = null;
                    } else if (isUrl(sourceText)) {
                        rawBytes = download(sourceText);
                        fileNameHint = lastUrlSegment(sourceText);
                    } else {
                        Path path = Path.of(sourceText);
                        if (!Files.exists(path)) throw new IOException(I18n.t("error.convert.fileNotFound", sourceText));
                        rawBytes = Files.readAllBytes(path);
                        fileNameHint = path.getFileName().toString();
                        sourcePath = path;
                    }

                    TableInputFormat resolvedFormat = requestedFormat == TableInputFormat.AUTO
                            ? TableConverter.detect(rawBytes, fileNameHint, charset)
                            : requestedFormat;
                    TableData table = TableConverter.parse(rawBytes, charset, resolvedFormat);

                    Path finalSourcePath = sourcePath;
                    if (outputFormat == TableOutputFormat.XLSX) {
                        byte[] xlsxBytes = TableConverter.writeXlsx(table);
                        Platform.runLater(() -> {
                            previewArea.setText(I18n.t("dialog.convert.xlsxPreviewPlaceholder",
                                    table.rows().size(), table.columns().size()));
                            lastBytesResult.set(xlsxBytes);
                            lastTextResult.set(null);
                            lastSourcePath.set(finalSourcePath);
                            saveAsButton.setDisable(false);
                            copyButton.setDisable(true); // binary output can't usefully go to a text clipboard
                            convertButton.setDisable(false);
                        });
                    } else {
                        String output = TableConverter.write(table, outputFormat, tableName, includeCreateTable, dialect);
                        Platform.runLater(() -> {
                            previewArea.setText(output);
                            lastTextResult.set(output);
                            lastBytesResult.set(null);
                            lastSourcePath.set(finalSourcePath);
                            saveAsButton.setDisable(false);
                            copyButton.setDisable(false);
                            convertButton.setDisable(false);
                        });
                    }
                } catch (IOException | InterruptedException | InvalidPathException ex) {
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
            if (lastTextResult.get() == null) return;
            ClipboardContent content = new ClipboardContent();
            content.putString(lastTextResult.get());
            Clipboard.getSystemClipboard().setContent(content);
        });

        saveAsButton.setOnAction(e -> {
            if (lastTextResult.get() == null && lastBytesResult.get() == null) return;
            TableOutputFormat outputFormat = outputFormatCombo.getValue();
            String extension = extensionFor(outputFormat);
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
                if (lastBytesResult.get() != null) {
                    Files.write(targetPath, lastBytesResult.get());
                } else {
                    Files.writeString(targetPath, lastTextResult.get(), StandardCharsets.UTF_8);
                }
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

        VBox sourceBox = new VBox(6, new Label(I18n.t("dialog.convert.source")), sourceRow, pasteArea);
        VBox.setVgrow(sourceBox, Priority.ALWAYS);
        VBox.setVgrow(pasteArea, Priority.ALWAYS);

        VBox centerBox = new VBox(8, sourceBox, optionsGrid, buttonBar, previewArea);
        centerBox.setPadding(new Insets(14));
        VBox.setVgrow(sourceBox, Priority.SOMETIMES);
        VBox.setVgrow(previewArea, Priority.ALWAYS);

        BorderPane dialogRoot = new BorderPane();
        dialogRoot.setCenter(centerBox);

        // Half the screen, centered. Computed explicitly rather than via Stage.centerOnScreen(),
        // which centers against whatever width/height the stage happens to already have at the
        // moment it's called — a value that isn't reliably settled yet immediately after show(),
        // and produced a visibly-off (non-vertically-centered) position in testing.
        Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();
        double width = screenBounds.getWidth() * 0.5;
        double height = screenBounds.getHeight() * 0.5;
        double x = screenBounds.getMinX() + (screenBounds.getWidth() - width) / 2;
        double y = screenBounds.getMinY() + (screenBounds.getHeight() - height) / 2;

        Stage dialogStage = new Stage();
        Scene scene = new Scene(dialogRoot, width, height);
        scene.getStylesheets().add(ConvertDialog.class.getResource("/css/dfiles.css").toExternalForm());
        dialogStage.setScene(scene);
        dialogStage.setTitle(I18n.t("dialog.convert.title"));
        dialogStage.initOwner(owner);
        dialogStage.initModality(Modality.WINDOW_MODAL);
        dialogStage.setX(x);
        dialogStage.setY(y);
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

    private static boolean isUrl(String text) {
        return URL_PREFIX.matcher(text.strip()).find();
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

    private static String fileNameOf(String pathText) {
        try {
            Path fileName = Path.of(pathText).getFileName();
            return fileName == null ? pathText : fileName.toString();
        } catch (InvalidPathException e) {
            return pathText;
        }
    }

    /** The first {@value #PREVIEW_MAX_LINES} lines of {@code text}, with a trailing marker if
     * there was more — used only for the source-preview area, never for the actual conversion. */
    private static String headSample(String text) {
        String[] lines = text.split("\r\n|\r|\n", -1);
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(lines.length, PREVIEW_MAX_LINES);
        for (int i = 0; i < shown; i++) {
            sb.append(lines[i]);
            if (i < shown - 1) sb.append('\n');
        }
        if (lines.length > PREVIEW_MAX_LINES) sb.append("\n…");
        return sb.toString();
    }

    private static String extensionFor(TableOutputFormat format) {
        return switch (format) {
            case SQL -> "sql";
            case CSV -> "csv";
            case TSV -> "tsv";
            case JSON -> "json";
            case XML -> "xml";
            case HTML_TABLE -> "html";
            case MARKDOWN_TABLE -> "md";
            case YAML -> "yaml";
            case XLSX -> "xlsx";
        };
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

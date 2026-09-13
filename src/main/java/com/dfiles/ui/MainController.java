package com.dfiles.ui;

import com.dfiles.db.Database;
import com.dfiles.i18n.I18n;
import com.dfiles.model.FileItem;
import com.dfiles.service.AiProvider;
import com.dfiles.service.AiScriptService;
import com.dfiles.service.ArchiveService;
import com.dfiles.service.DesktopOpener;
import com.dfiles.service.DirectoryScanner;
import com.dfiles.service.FileTypeUtil;
import com.dfiles.service.FolderWatcherService;
import com.dfiles.service.GitService;
import com.dfiles.service.ScriptRunner;
import com.dfiles.service.TerminalLauncher;
import com.dfiles.service.TextPreviewService;
import com.dfiles.service.VSCodeLauncher;
import com.dfiles.util.FileSizeFormatter;
import com.dfiles.util.PosixInfoFormatter;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Glues the left places panel, the file table, the toolbar and all background services together. */
public class MainController {

    private final Stage stage;
    private static final Logger LOGGER = LogManager.getLogger(MainController.class);

    private final Database db;
    private final DirectoryScanner scanner = new DirectoryScanner();
    private final FolderWatcherService watcher = new FolderWatcherService();
    private final GitService git = new GitService();

    private final LeftPane leftPane = new LeftPane();
    private final FileTablePane filesPane = new FileTablePane();
    private final ScriptDevPane scriptDevPane = new ScriptDevPane();
    private javafx.scene.control.TabPane centerTabs;
    private javafx.scene.control.Tab filesTab;
    private javafx.scene.control.Tab scriptDevTab;

    private final BorderPane root = new BorderPane();
    private final Label statusLabel = new Label();
    private final Label watchingLabel = new Label();
    private final TextField addressField = new TextField();
    private final TextField searchField = new TextField();
    private final ToggleButton showHiddenToggle = new ToggleButton();
    private final TextArea gitOutputArea = new TextArea();
    private final VBox gitOutputPanel = new VBox();

    private Button backBtn, forwardBtn, upBtn, refreshBtn, newFolderBtn, newFileBtn, deleteBtn, terminalBtn, toggleSidebarBtn, copyListBtn;
    private MenuButton gitMenu;
    private ComboBox<Locale> languageCombo;
    private Button helpBtn, aboutBtn, closeBtn;

    private static final String APP_VERSION = "1.0.0";

    private final Deque<Path> backStack = new ArrayDeque<>();
    private final Deque<Path> forwardStack = new ArrayDeque<>();
    private Path currentDirectory;
    private List<FileItem> currentItemsUnfiltered = new ArrayList<>();

    private List<Path> clipboardPaths = new ArrayList<>();
    private boolean clipboardCut = false;
    private boolean watchingActive = false;

    private javafx.scene.control.SplitPane mainSplit;
    private boolean leftPanelVisible = true;
    private double lastLeftDividerPosition = 0.2;

    public MainController(Stage stage, Database db) {
        this.stage = stage;
        this.db = db;
    }

    public BorderPane buildUI() {
        root.setTop(buildTopArea());

        // Tab 1 is the existing file browser; Tab 2 is the AI-assisted script workspace.
        centerTabs = new javafx.scene.control.TabPane();
        centerTabs.setTabClosingPolicy(javafx.scene.control.TabPane.TabClosingPolicy.UNAVAILABLE);
        filesTab = new javafx.scene.control.Tab(I18n.t("tabs.files"), buildCenterArea());
        scriptDevTab = new javafx.scene.control.Tab(I18n.t("tabs.scriptDev"), scriptDevPane.getNode());
        centerTabs.getTabs().addAll(filesTab, scriptDevTab);

        // Draggable divider between the sidebar and the file table, so both can be resized.
        mainSplit = new javafx.scene.control.SplitPane(leftPane.getNode(), centerTabs);
        mainSplit.setDividerPositions(0.2);
        javafx.scene.control.SplitPane.setResizableWithParent(leftPane.getNode(), false);
        root.setCenter(mainSplit);
        root.setBottom(buildStatusBar());

        leftPane.setOnNavigate(this::navigateTo);
        leftPane.setOnAddFolder(this::addCustomFolderDialog);
        leftPane.setOnShowProperties(this::showProperties);
        leftPane.setOnRemoveBookmark(id -> {
            db.removeCustomFolder(id);
            leftPane.setBookmarks(db.listCustomFolders());
        });
        leftPane.setBookmarks(db.listCustomFolders());

        leftPane.setOnSelectScript(this::openScript);
        leftPane.setOnAddScript(this::addScriptDialog);
        leftPane.setOnRenameScript(this::renameScriptDialog);
        leftPane.setOnDeleteScript(this::deleteScriptDialog);
        leftPane.setScripts(db.listScripts());

        scriptDevPane.setOnRunPrompt(this::runAiPrompt);
        scriptDevPane.setOnRunScript(this::runCurrentScript);
        scriptDevPane.setOnSave(this::saveCurrentScript);
        scriptDevPane.setOnProviderChange(provider -> db.setSetting("ai_provider", provider.name()));
        scriptDevPane.setProvider(AiProvider.fromSettingValue(db.getSetting("ai_provider", null)));

        filesPane.setOnOpen(this::openItem);
        filesPane.setOnSelectionChanged(sel -> updateStatusBar());
        filesPane.setContextMenuFactory(this::buildContextMenu);

        filesPane.getTableView().setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DELETE) {
                deleteItems(filesPane.getSelectedItems());
            } else if (e.getCode() == KeyCode.F2) {
                List<FileItem> sel = filesPane.getSelectedItems();
                if (sel.size() == 1) renameItem(sel.get(0));
            } else if (e.getCode() == KeyCode.BACK_SPACE) {
                up();
            } else if (e.getCode() == KeyCode.ENTER) {
                List<FileItem> sel = filesPane.getSelectedItems();
                if (sel.size() == 1) openItem(sel.get(0));
            }
        });

        installSortPersistence();

        String startPath = db.getSetting("last_directory", System.getProperty("user.home"));
        Path start = Files.isDirectory(Paths.get(startPath)) ? Paths.get(startPath) : Paths.get(System.getProperty("user.home"));
        navigateTo(start);

        return root;
    }

    // ---------------- top toolbar / address bar ----------------

    private VBox buildTopArea() {
        toggleSidebarBtn = iconButton("☰", I18n.t("toolbar.toggleSidebar"), e -> toggleLeftPanel());
        backBtn = iconButton("◀", I18n.t("toolbar.back"), e -> back());
        forwardBtn = iconButton("▶", I18n.t("toolbar.forward"), e -> forward());
        upBtn = iconButton("⬆", I18n.t("toolbar.up"), e -> up());
        refreshBtn = iconButton("⟳", I18n.t("toolbar.refresh"), e -> refresh());
        newFolderBtn = compositeIconButton("🗀", "+", I18n.t("toolbar.newFolder"), e -> createNewFolder());
        newFileBtn = compositeIconButton("🗎", "+", I18n.t("toolbar.newFile"), e -> createNewFile());
        deleteBtn = iconButton("🗑", I18n.t("toolbar.delete"), e -> deleteItems(filesPane.getSelectedItems()));
        terminalBtn = iconButton("⌥", I18n.t("toolbar.terminal"), e -> openTerminal());
        copyListBtn = iconButton("📋", I18n.t("toolbar.copyList"), e -> copyFileListToClipboard());

        Label showHiddenIcon = new Label("👁");
        showHiddenIcon.getStyleClass().add("icon-glyph");
        showHiddenToggle.setGraphic(showHiddenIcon);
        showHiddenToggle.setTooltip(new Tooltip(I18n.t("toolbar.showHidden")));
        showHiddenToggle.setSelected(Boolean.parseBoolean(db.getSetting("show_hidden", "false")));
        showHiddenToggle.setOnAction(e -> {
            db.setSetting("show_hidden", String.valueOf(showHiddenToggle.isSelected()));
            applyFilterAndDisplay();
        });

        gitMenu = new MenuButton(I18n.t("toolbar.git"));
        MenuItem gitStatus = new MenuItem(I18n.t("toolbar.gitStatus"));
        gitStatus.setOnAction(e -> git.status(currentDirectory, this::showGitResult));
        MenuItem gitPull = new MenuItem(I18n.t("toolbar.gitPull"));
        gitPull.setOnAction(e -> git.pull(currentDirectory, r -> { showGitResult(r); refresh(); }));
        MenuItem gitPush = new MenuItem(I18n.t("toolbar.gitPush"));
        gitPush.setOnAction(e -> git.push(currentDirectory, this::showGitResult));
        MenuItem gitCommit = new MenuItem(I18n.t("toolbar.gitCommit"));
        gitCommit.setOnAction(e -> commitDialog());
        MenuItem gitLog = new MenuItem(I18n.t("toolbar.gitLog"));
        gitLog.setOnAction(e -> git.log(currentDirectory, this::showGitResult));
        MenuItem gitInit = new MenuItem(I18n.t("toolbar.gitInit"));
        gitInit.setOnAction(e -> git.init(currentDirectory, r -> { showGitResult(r); refresh(); }));
        gitMenu.getItems().addAll(gitStatus, gitPull, gitPush, gitCommit, gitLog, new SeparatorMenuItem(), gitInit);

        searchField.setPromptText(I18n.t("toolbar.search"));
        searchField.textProperty().addListener((obs, old, val) -> applyFilterAndDisplay());
        HBox.setHgrow(searchField, Priority.ALWAYS);

        languageCombo = new ComboBox<>();
        languageCombo.getItems().addAll(I18n.SUPPORTED);
        languageCombo.setValue(I18n.getLocale());
        languageCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Locale l) { return l == null ? "" : I18n.displayNameFor(l); }
            @Override public Locale fromString(String s) { return I18n.getLocale(); }
        });
        languageCombo.setOnAction(e -> switchLocale(languageCombo.getValue()));

        helpBtn = iconButton("?", I18n.t("toolbar.help"), e -> openHelp());
        aboutBtn = iconButton("ℹ", I18n.t("menu.about"), e -> showAboutDialog());
        closeBtn = iconButton("✕", I18n.t("toolbar.close"), e -> requestExit());

        ToolBar toolBar = new ToolBar(toggleSidebarBtn, new javafx.scene.control.Separator(),
                backBtn, forwardBtn, upBtn, refreshBtn, new javafx.scene.control.Separator(),
                newFolderBtn, newFileBtn, deleteBtn, new javafx.scene.control.Separator(),
                showHiddenToggle, gitMenu, terminalBtn, copyListBtn, new javafx.scene.control.Separator(),
                searchField, languageCombo, helpBtn, aboutBtn, closeBtn);

        addressField.setOnAction(e -> {
            Path p = Paths.get(addressField.getText().trim());
            if (Files.isDirectory(p)) {
                navigateTo(p);
            } else {
                showError(I18n.t("dialog.error.title"), "Not a valid directory: " + p);
                addressField.setText(currentDirectory.toString());
            }
        });
        HBox addressBar = new HBox(new Label("  "), addressField);
        HBox.setHgrow(addressField, Priority.ALWAYS);
        addressBar.setPadding(new Insets(4, 8, 4, 8));
        addressBar.setAlignment(Pos.CENTER_LEFT);

        VBox top = new VBox(toolBar, addressBar);
        return top;
    }

    private static final String GITHUB_URL = "https://github.com/danielscarvalho/DFiles";

    private void showAboutDialog() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(I18n.t("about.title"));
        alert.setHeaderText("DFiles " + APP_VERSION);
        alert.setContentText(I18n.t("about.body"));
        alert.getDialogPane().setMinWidth(480);

        var logoStream = getClass().getResourceAsStream("/icons/DFile-Logo.png");
        if (logoStream != null) {
            javafx.scene.image.ImageView logo = new javafx.scene.image.ImageView(new javafx.scene.image.Image(logoStream));
            logo.setFitWidth(64);
            logo.setFitHeight(64);
            alert.setGraphic(logo);
        }

        ButtonType githubButton = new ButtonType(I18n.t("about.viewOnGitHub"));
        alert.getButtonTypes().setAll(githubButton, ButtonType.CLOSE);

        alert.showAndWait().ifPresent(response -> {
            if (response == githubButton) {
                try {
                    DesktopOpener.open(GITHUB_URL);
                } catch (IOException e) {
                    LOGGER.error("Could not open {}", GITHUB_URL, e);
                    showError(I18n.t("dialog.error.title"), I18n.t("error.openUrl", GITHUB_URL));
                }
            }
        });
    }

    /** Unpacks the bundled help pages next to the database (so they survive as real files the
     * OS browser can open) and launches the one matching the current UI language with the
     * system default application — kept off the FX thread for the same reason DesktopOpener
     * always is.
     *
     * <p>Every supported language's help file is extracted alongside the one that gets opened
     * (not just that one) so the in-page language-switcher links between them resolve to real
     * files instead of 404ing. Falls back to {@code help_en.html} if a translation for the
     * current language isn't bundled. */
    private void openHelp() {
        Thread worker = new Thread(() -> {
            try {
                Path helpDir = Paths.get(System.getProperty("user.home"), ".dfiles");
                Files.createDirectories(helpDir);
                for (java.util.Locale locale : I18n.SUPPORTED) {
                    String name = "help_" + locale.getLanguage() + ".html";
                    if (getClass().getResource("/help/" + name) != null) {
                        extractResource("/help/" + name, helpDir.resolve(name));
                    }
                }
                extractResource("/help/screenshot.png", helpDir.resolve("screenshot.png"));

                String resourceName = "help_" + I18n.getLocale().getLanguage() + ".html";
                if (getClass().getResource("/help/" + resourceName) == null) {
                    resourceName = "help_en.html";
                }
                DesktopOpener.open(helpDir.resolve(resourceName));
            } catch (IOException e) {
                LOGGER.error("Could not open help page", e);
                Platform.runLater(() -> showError(I18n.t("dialog.error.title"), I18n.t("error.help", e.getMessage())));
            }
        }, "dfiles-open-help");
        worker.setDaemon(true);
        worker.start();
    }

    private void extractResource(String resourcePath, Path destination) throws IOException {
        try (var in = getClass().getResourceAsStream(resourcePath)) {
            if (in == null) throw new IOException("Bundled resource not found: " + resourcePath);
            Files.copy(in, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Button iconButton(String glyph, String tooltip, javafx.event.EventHandler<javafx.event.ActionEvent> handler) {
        Label iconLabel = new Label(glyph);
        iconLabel.getStyleClass().add("icon-glyph");
        Button b = new Button();
        b.setGraphic(iconLabel);
        b.setTooltip(new Tooltip(tooltip));
        b.setOnAction(handler);
        return b;
    }

    /**
     * A button combining an emoji glyph (which may fail to render if the OS has no color
     * emoji font installed) with a plain-ASCII badge (e.g. "+") that is guaranteed to
     * render in any font, so the button never appears completely blank.
     */
    private Button compositeIconButton(String glyph, String badge, String tooltip, javafx.event.EventHandler<javafx.event.ActionEvent> handler) {
        Label iconLabel = new Label(glyph);
        iconLabel.getStyleClass().add("icon-glyph");
        Label badgeLabel = new Label(badge);
        badgeLabel.getStyleClass().add("icon-badge");
        HBox box = new HBox(1, iconLabel, badgeLabel);
        box.setAlignment(Pos.CENTER);
        Button b = new Button();
        b.setGraphic(box);
        b.setTooltip(new Tooltip(tooltip));
        b.setOnAction(handler);
        return b;
    }

    // ---------------- center: file table + collapsible git output ----------------

    private BorderPane buildCenterArea() {
        gitOutputArea.setEditable(false);
        gitOutputArea.setPrefRowCount(8);
        gitOutputArea.getStyleClass().add("git-output");
        Label gitHeader = new Label(I18n.t("git.output"));
        Button closeGit = new Button("✕");
        closeGit.setOnAction(e -> gitOutputPanel.setVisible(false));
        HBox gitHeaderBar = new HBox(gitHeader, spacer(), closeGit);
        gitHeaderBar.setAlignment(Pos.CENTER_LEFT);
        gitOutputPanel.getChildren().addAll(gitHeaderBar, gitOutputArea);
        gitOutputPanel.setVisible(false);
        gitOutputPanel.managedProperty().bind(gitOutputPanel.visibleProperty());
        gitOutputPanel.getStyleClass().add("git-panel");

        BorderPane center = new BorderPane();
        center.setCenter(filesPane.getTableView());
        center.setBottom(gitOutputPanel);
        return center;
    }

    private javafx.scene.layout.Region spacer() {
        javafx.scene.layout.Region r = new javafx.scene.layout.Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    private HBox buildStatusBar() {
        watchingLabel.getStyleClass().add("status-dim");
        HBox bar = new HBox(statusLabel, spacer(), watchingLabel);
        bar.setPadding(new Insets(4, 10, 4, 10));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    // ---------------- navigation ----------------

    public void navigateTo(Path dir) {
        navigateTo(dir, true);
    }

    private void navigateTo(Path dir, boolean pushHistory) {
        LOGGER.info("Navigating to {}", dir);
        if (currentDirectory != null && pushHistory) {
            backStack.push(currentDirectory);
            forwardStack.clear();
        }
        currentDirectory = dir;
        addressField.setText(dir.toString());
        leftPane.selectPath(dir);
        updateNavButtons();
        db.setSetting("last_directory", dir.toString());
        updateGitControls();
        load(dir);
    }

    private void back() {
        if (backStack.isEmpty()) return;
        forwardStack.push(currentDirectory);
        Path p = backStack.pop();
        navigateTo(p, false);
    }

    private void forward() {
        if (forwardStack.isEmpty()) return;
        backStack.push(currentDirectory);
        Path p = forwardStack.pop();
        navigateTo(p, false);
    }

    private void up() {
        Path parent = currentDirectory.getParent();
        if (parent != null) navigateTo(parent);
    }

    private void updateNavButtons() {
        backBtn.setDisable(backStack.isEmpty());
        forwardBtn.setDisable(forwardStack.isEmpty());
        upBtn.setDisable(currentDirectory.getParent() == null);
    }

    private void refresh() {
        load(currentDirectory);
    }

    private void toggleLeftPanel() {
        if (leftPanelVisible) {
            double[] positions = mainSplit.getDividerPositions();
            if (positions.length > 0) lastLeftDividerPosition = positions[0];
            mainSplit.getItems().remove(leftPane.getNode());
        } else {
            mainSplit.getItems().add(0, leftPane.getNode());
            mainSplit.setDividerPositions(lastLeftDividerPosition);
        }
        leftPanelVisible = !leftPanelVisible;
    }

    // ---------------- loading (cache-first, then live scan + watch) ----------------

    private void load(Path dir) {
        statusLabel.setText(I18n.t("status.loading"));

        List<FileItem> cached = db.loadCachedFolder(dir.toString());
        if (!cached.isEmpty()) {
            currentItemsUnfiltered = cached;
            applyFilterAndDisplay();
        }

        scanner.scanAsync(dir,
                items -> {
                    if (!dir.equals(currentDirectory)) return; // user navigated away before scan finished
                    currentItemsUnfiltered = items;
                    applyFilterAndDisplay();
                    restoreSortPrefs();
                    db.replaceCachedFolder(dir.toString(), items);
                    watcher.watch(dir, this::refresh);
                    watchingActive = true;
                    watchingLabel.setText(I18n.t("status.watching"));
                },
                ex -> {
                    if (!dir.equals(currentDirectory)) return;
                    statusLabel.setText(ex.getMessage());
                });
    }

    private void applyFilterAndDisplay() {
        boolean showHidden = showHiddenToggle.isSelected();
        String query = searchField.getText() == null ? "" : searchField.getText().toLowerCase(Locale.ROOT);
        List<FileItem> filtered = currentItemsUnfiltered.stream()
                .filter(i -> showHidden || !i.isHidden())
                .filter(i -> query.isEmpty() || i.getName().toLowerCase(Locale.ROOT).contains(query))
                .toList();
        filesPane.setItems(filtered);
        updateStatusBar();
    }

    private void updateStatusBar() {
        int total = filesPane.getTableView().getItems().size();
        List<FileItem> selection = filesPane.getSelectedItems();
        String itemsText = I18n.t("status.items", total);
        if (selection.size() == 1) {
            String posixInfo = PosixInfoFormatter.format(selection.get(0).getPath(), I18n.getLocale());
            statusLabel.setText(posixInfo != null ? itemsText + "  •  " + posixInfo : itemsText);
        } else if (selection.size() > 1) {
            statusLabel.setText(itemsText + "  •  " + I18n.t("status.selected", selection.size()));
        } else {
            statusLabel.setText(itemsText);
        }
    }

    // ---------------- sort persistence ----------------

    private void installSortPersistence() {
        filesPane.getTableView().getSortOrder().addListener((javafx.collections.ListChangeListener<Object>) c -> {
            if (currentDirectory == null || filesPane.getTableView().getSortOrder().isEmpty()) return;
            var col = filesPane.getTableView().getSortOrder().get(0);
            String colId = colIdOf(col);
            boolean asc = col.getSortType() == javafx.scene.control.TableColumn.SortType.ASCENDING;
            db.saveFolderPrefs(currentDirectory.toString(), colId, asc, showHiddenToggle.isSelected());
        });
    }

    private String colIdOf(javafx.scene.control.TableColumn<?, ?> col) {
        int index = filesPane.getTableView().getColumns().indexOf(col);
        return switch (index) {
            case 0 -> "name";
            case 1 -> "size";
            case 2 -> "type";
            case 3 -> "modified";
            default -> "name";
        };
    }

    private void restoreSortPrefs() {
        if (currentDirectory == null) return;
        String[] prefs = db.getFolderPrefs(currentDirectory.toString());
        if (prefs == null || prefs[0] == null) return;
        int index = switch (prefs[0]) {
            case "size" -> 1;
            case "type" -> 2;
            case "modified" -> 3;
            default -> 0;
        };
        var col = filesPane.getTableView().getColumns().get(index);
        col.setSortType("1".equals(prefs[1])
                ? javafx.scene.control.TableColumn.SortType.ASCENDING
                : javafx.scene.control.TableColumn.SortType.DESCENDING);
        filesPane.getTableView().getSortOrder().setAll(col);
        filesPane.getTableView().sort();
    }

    // ---------------- opening files ----------------

    private void openItem(FileItem item) {
        if (item.isDirectory()) {
            navigateTo(item.getPath());
        } else if (ArchiveService.isArchive(item.getPath())) {
            showArchiveDialog(item);
        } else {
            // Launched off the FX thread: shelling out is normally fast, but never risk
            // stalling the UI on a slow/misbehaving "open" handler on the host OS.
            Thread opener = new Thread(() -> {
                try {
                    DesktopOpener.open(item.getPath());
                } catch (IOException e) {
                    LOGGER.error("Failed to open file: {}", item.getPath(), e);
                    Platform.runLater(() ->
                            showError(I18n.t("dialog.error.title"), I18n.t("error.openFile", item.getName())));
                }
            }, "dfiles-open-file");
            opener.setDaemon(true);
            opener.start();
        }
    }

    private void openTerminal() {
        if (!TerminalLauncher.open(currentDirectory)) {
            LOGGER.error("Could not find a terminal emulator to open at {}", currentDirectory);
            showError(I18n.t("dialog.error.title"), I18n.t("error.terminal"));
        }
    }

    private void copyFileListToClipboard() {
        List<FileItem> items = filesPane.getTableView().getItems();
        StringBuilder sb = new StringBuilder();
        for (FileItem item : items) {
            sb.append(item.getName()).append('\t')
                    .append(FileSizeFormatter.formatSize(item.getSize(), item.isDirectory())).append('\t')
                    .append(item.getTypeLabel()).append('\t')
                    .append(FileSizeFormatter.formatDate(item.getLastModified())).append('\n');
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(sb.toString());
        Clipboard.getSystemClipboard().setContent(content);
        LOGGER.info("Copied file list of {} ({} items) to clipboard", currentDirectory, items.size());

        statusLabel.setText(I18n.t("status.copiedList", items.size()));
        javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(2));
        pause.setOnFinished(e -> updateStatusBar());
        pause.play();
    }

    // ---------------- file operations ----------------

    private void createNewFolder() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle(I18n.t("dialog.newFolder.title"));
        dialog.setHeaderText(I18n.t("dialog.newFolder.header"));
        dialog.setContentText(I18n.t("dialog.newFolder.prompt"));
        dialog.showAndWait().ifPresent(name -> {
            if (name.isBlank()) return;
            try {
                Files.createDirectory(currentDirectory.resolve(name));
                refresh();
            } catch (IOException e) {
                LOGGER.error("Could not create folder {} in {}", name, currentDirectory, e);
                showError(I18n.t("dialog.error.title"), I18n.t("error.createFolder", e.getMessage()));
            }
        });
    }

    private void createNewFile() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle(I18n.t("dialog.newFile.title"));
        dialog.setHeaderText(I18n.t("dialog.newFile.header"));
        dialog.setContentText(I18n.t("dialog.newFile.prompt"));
        dialog.showAndWait().ifPresent(name -> {
            if (name.isBlank()) return;
            try {
                Files.createFile(currentDirectory.resolve(name));
                refresh();
            } catch (IOException e) {
                LOGGER.error("Could not create file {} in {}", name, currentDirectory, e);
                showError(I18n.t("dialog.error.title"), I18n.t("error.createFile", e.getMessage()));
            }
        });
    }

    private void renameItem(FileItem item) {
        TextInputDialog dialog = new TextInputDialog(item.getName());
        dialog.setTitle(I18n.t("dialog.rename.title"));
        dialog.setHeaderText(I18n.t("dialog.rename.header").formatted(item.getName()));
        dialog.setContentText(I18n.t("dialog.rename.prompt"));
        dialog.showAndWait().ifPresent(newName -> {
            if (newName.isBlank() || newName.equals(item.getName())) return;
            try {
                Files.move(item.getPath(), item.getPath().resolveSibling(newName));
                refresh();
            } catch (IOException e) {
                LOGGER.error("Could not rename {} to {}", item.getPath(), newName, e);
                showError(I18n.t("dialog.error.title"), I18n.t("error.rename", e.getMessage()));
            }
        });
    }

    private void deleteItems(List<FileItem> items) {
        if (items.isEmpty()) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(I18n.t("dialog.delete.title"));
        confirm.setHeaderText(I18n.t("dialog.delete.header").formatted(items.size()));
        confirm.setContentText(I18n.t("dialog.delete.content"));
        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) return;

        for (FileItem item : items) {
            try {
                deleteRecursively(item.getPath());
                LOGGER.info("Deleted {}", item.getPath());
            } catch (IOException e) {
                LOGGER.error("Could not delete {}", item.getPath(), e);
                showError(I18n.t("dialog.error.title"), I18n.t("error.delete", e.getMessage()));
            }
        }
        refresh();
    }

    private void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                for (Path child : stream) deleteRecursively(child);
            }
        }
        Files.delete(path);
    }

    private void copySelection(List<FileItem> items, boolean cut) {
        clipboardPaths = items.stream().map(FileItem::getPath).toList();
        clipboardCut = cut;
        Clipboard clipboard = Clipboard.getSystemClipboard();
        ClipboardContent content = new ClipboardContent();
        content.putFiles(clipboardPaths.stream().map(Path::toFile).toList());
        clipboard.setContent(content);
    }

    private void pasteClipboard() {
        if (clipboardPaths.isEmpty()) return;
        for (Path source : clipboardPaths) {
            try {
                Path dest = uniqueDestination(currentDirectory, source.getFileName().toString());
                if (clipboardCut) {
                    Files.move(source, dest);
                } else {
                    copyRecursively(source, dest);
                }
            } catch (IOException e) {
                LOGGER.error("Could not paste {} into {}", source, currentDirectory, e);
                showError(I18n.t("dialog.error.title"), e.getMessage());
            }
        }
        if (clipboardCut) clipboardPaths = new ArrayList<>();
        refresh();
    }

    private Path uniqueDestination(Path targetDir, String fileName) {
        Path dest = targetDir.resolve(fileName);
        if (!Files.exists(dest)) return dest;
        String base = fileName;
        String ext = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            base = fileName.substring(0, dot);
            ext = fileName.substring(dot);
        }
        int counter = 2;
        Path candidate = targetDir.resolve(base + " (copy)" + ext);
        while (Files.exists(candidate)) {
            candidate = targetDir.resolve(base + " (copy " + counter + ")" + ext);
            counter++;
        }
        return candidate;
    }

    private void copyRecursively(Path source, Path dest) throws IOException {
        if (Files.isDirectory(source)) {
            Files.createDirectories(dest);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(source)) {
                for (Path child : stream) {
                    copyRecursively(child, dest.resolve(child.getFileName()));
                }
            }
        } else {
            Files.copy(source, dest);
        }
    }

    // ---------------- context menu ----------------

    private ContextMenu buildContextMenu(FileItem item) {
        ContextMenu menu = new ContextMenu();
        if (item != null) {
            List<FileItem> selection = filesPane.getSelectedItems().isEmpty() ? List.of(item) : filesPane.getSelectedItems();
            MenuItem open = new MenuItem(I18n.t("context.open"));
            open.setOnAction(e -> openItem(item));
            MenuItem openWith = new MenuItem(I18n.t("context.openWith"));
            openWith.setOnAction(e -> { try { DesktopOpener.open(item.getPath()); } catch (IOException ignored) {} });
            MenuItem openTerminalHere = new MenuItem(I18n.t("context.openTerminalHere"));
            openTerminalHere.setOnAction(e -> TerminalLauncher.open(item.isDirectory() ? item.getPath() : item.getPath().getParent()));
            MenuItem openInVSCode = new MenuItem(I18n.t("context.openInVSCode"));
            openInVSCode.setOnAction(e -> openInVSCode(item.isDirectory() ? item.getPath() : item.getPath().getParent()));
            MenuItem cut = new MenuItem(I18n.t("context.cut"));
            cut.setOnAction(e -> copySelection(selection, true));
            MenuItem copy = new MenuItem(I18n.t("context.copy"));
            copy.setOnAction(e -> copySelection(selection, false));
            MenuItem rename = new MenuItem(I18n.t("context.rename"));
            rename.setOnAction(e -> renameItem(item));
            rename.setDisable(selection.size() != 1);
            MenuItem delete = new MenuItem(I18n.t("context.delete"));
            delete.setOnAction(e -> deleteItems(selection));
            MenuItem copyPath = new MenuItem(I18n.t("context.copyPath"));
            copyPath.setOnAction(e -> {
                ClipboardContent content = new ClipboardContent();
                content.putString(item.getPath().toString());
                Clipboard.getSystemClipboard().setContent(content);
            });
            MenuItem properties = new MenuItem(I18n.t("context.properties"));
            properties.setOnAction(e -> showProperties(item.getPath()));

            menu.getItems().addAll(open, openWith, openTerminalHere, openInVSCode, new SeparatorMenuItem(),
                    cut, copy, rename, delete, new SeparatorMenuItem(), copyPath, properties);

            if (!item.isDirectory() && FileTypeUtil.isTextLike(item.getPath())) {
                MenuItem viewHeadTail = new MenuItem(I18n.t("context.viewHeadTail"));
                viewHeadTail.setOnAction(e -> showHeadTailDialog(item));
                MenuItem findReplace = new MenuItem(I18n.t("context.findReplace"));
                findReplace.setOnAction(e -> showFindReplaceDialog(item));
                menu.getItems().addAll(viewHeadTail, findReplace);
            }
            if (!item.isDirectory() && ArchiveService.isArchive(item.getPath())) {
                MenuItem showContents = new MenuItem(I18n.t("context.showArchiveContents"));
                showContents.setOnAction(e -> showArchiveDialog(item));
                MenuItem extractHere = new MenuItem(I18n.t("context.extractHere"));
                extractHere.setOnAction(e -> extractArchive(item.getPath()));
                menu.getItems().addAll(showContents, extractHere);
            }
            if (item.isDirectory()) {
                MenuItem compress = new MenuItem(I18n.t("context.compress"));
                compress.setOnAction(e -> showCompressDialog(item.getPath()));
                menu.getItems().add(compress);
            }
        } else {
            MenuItem newFolder = new MenuItem(I18n.t("context.newFolder"));
            newFolder.setOnAction(e -> createNewFolder());
            MenuItem newFile = new MenuItem(I18n.t("context.newFile"));
            newFile.setOnAction(e -> createNewFile());
            MenuItem paste = new MenuItem(I18n.t("context.paste"));
            paste.setDisable(clipboardPaths.isEmpty());
            paste.setOnAction(e -> pasteClipboard());
            MenuItem openInVSCode = new MenuItem(I18n.t("context.openInVSCode"));
            openInVSCode.setOnAction(e -> openInVSCode(currentDirectory));
            MenuItem refreshItem = new MenuItem(I18n.t("context.refresh"));
            refreshItem.setOnAction(e -> refresh());
            menu.getItems().addAll(newFolder, newFile, paste, openInVSCode, new SeparatorMenuItem(), refreshItem);
        }
        return menu;
    }

    private void openInVSCode(Path directory) {
        if (!VSCodeLauncher.open(directory)) {
            showError(I18n.t("dialog.error.title"), I18n.t("error.vscode"));
        }
    }

    private void showProperties(Path path) {
        boolean directory = Files.isDirectory(path);
        String sizeLabel = directory ? I18n.t("properties.contains") : I18n.t("properties.size");
        String sizeValue = directory ? countItemsLabel(path) : sizeForFile(path);

        long modified;
        try {
            modified = Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            LOGGER.warn("Could not read last-modified time for {}", path, e);
            modified = 0;
        }

        StringBuilder content = new StringBuilder();
        content.append(I18n.t("properties.path")).append(": ").append(path).append('\n');
        content.append(I18n.t("properties.type")).append(": ").append(FileTypeUtil.label(path, directory)).append('\n');
        content.append(sizeLabel).append(": ").append(sizeValue).append('\n');
        content.append(I18n.t("properties.modified")).append(": ").append(FileSizeFormatter.formatDate(modified));
        String posixInfo = PosixInfoFormatter.format(path, I18n.getLocale());
        if (posixInfo != null) {
            content.append('\n').append(I18n.t("properties.permissions")).append(": ").append(posixInfo);
        }

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(I18n.t("context.properties"));
        alert.setHeaderText(path.getFileName() != null ? path.getFileName().toString() : path.toString());
        alert.setContentText(content.toString());
        alert.getDialogPane().setMinWidth(480);
        alert.showAndWait();
    }

    private String sizeForFile(Path path) {
        try {
            return FileSizeFormatter.formatSize(Files.size(path), false);
        } catch (IOException e) {
            LOGGER.warn("Could not read size of {}", path, e);
            return "?";
        }
    }

    private static final int MAX_COUNTED_ITEMS = 10_000;

    private String countItemsLabel(Path directory) {
        int count = 0;
        boolean truncated = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path ignored : stream) {
                count++;
                if (count >= MAX_COUNTED_ITEMS) {
                    truncated = true;
                    break;
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Could not count items in {}", directory, e);
            return "?";
        }
        return I18n.t("status.items", count) + (truncated ? "+" : "");
    }

    private void showHeadTailDialog(FileItem item) {
        TextPreviewService.Preview preview;
        try {
            preview = TextPreviewService.read(item.getPath(), TextPreviewService.MAX_LINES);
        } catch (IOException e) {
            LOGGER.error("Could not read file for head/tail preview: {}", item.getPath(), e);
            showError(I18n.t("dialog.error.title"), I18n.t("error.preview", e.getMessage()));
            return;
        }

        String divider = "─".repeat(70);
        StringBuilder content = new StringBuilder();
        if (preview.isFullFile) {
            content.append(I18n.t("dialog.headtail.full", preview.headLines.size())).append('\n').append(divider).append('\n');
            preview.headLines.forEach(l -> content.append(l).append('\n'));
        } else {
            content.append(I18n.t("dialog.headtail.head", preview.headLines.size())).append('\n').append(divider).append('\n');
            preview.headLines.forEach(l -> content.append(l).append('\n'));
            content.append('\n').append(I18n.t("dialog.headtail.tail", preview.tailLines.size())).append('\n').append(divider).append('\n');
            preview.tailLines.forEach(l -> content.append(l).append('\n'));
        }

        TextArea textArea = new TextArea(content.toString());
        textArea.setEditable(false);
        textArea.setWrapText(false);
        textArea.getStyleClass().add("terminal-output");

        Stage dialogStage = new Stage();
        Button closeButton = new Button(I18n.t("dialog.headtail.close"));
        closeButton.setOnAction(e -> dialogStage.close());
        HBox buttonBar = new HBox(closeButton);
        buttonBar.setAlignment(Pos.CENTER_RIGHT);
        buttonBar.setPadding(new Insets(8));

        BorderPane dialogRoot = new BorderPane();
        dialogRoot.setCenter(textArea);
        dialogRoot.setBottom(buttonBar);

        Scene scene = new Scene(dialogRoot, 820, 600);
        scene.getStylesheets().add(getClass().getResource("/css/dfiles.css").toExternalForm());
        dialogStage.setScene(scene);
        dialogStage.setTitle(I18n.t("dialog.headtail.title", item.getName()));
        dialogStage.initOwner(stage);
        dialogStage.initModality(Modality.WINDOW_MODAL);
        dialogStage.show();
    }

    // ---------------- archives ----------------

    private void showArchiveDialog(FileItem item) {
        TextArea textArea = new TextArea(I18n.t("status.loading"));
        textArea.setEditable(false);
        textArea.setWrapText(false);
        textArea.getStyleClass().add("terminal-output");

        Stage dialogStage = new Stage();
        Button extractButton = new Button(I18n.t("context.extractHere"));
        extractButton.setOnAction(e -> {
            extractArchive(item.getPath());
            dialogStage.close();
        });
        Button closeButton = new Button(I18n.t("dialog.headtail.close"));
        closeButton.setOnAction(e -> dialogStage.close());
        HBox buttonBar = new HBox(extractButton, spacer(), closeButton);
        buttonBar.setAlignment(Pos.CENTER_LEFT);
        buttonBar.setPadding(new Insets(8));

        BorderPane dialogRoot = new BorderPane();
        dialogRoot.setCenter(textArea);
        dialogRoot.setBottom(buttonBar);

        Scene scene = new Scene(dialogRoot, 820, 600);
        scene.getStylesheets().add(getClass().getResource("/css/dfiles.css").toExternalForm());
        dialogStage.setScene(scene);
        dialogStage.setTitle(I18n.t("dialog.archive.title", item.getName()));
        dialogStage.initOwner(stage);
        dialogStage.initModality(Modality.WINDOW_MODAL);
        dialogStage.show();

        Thread loader = new Thread(() -> {
            try {
                String contents = ArchiveService.listContents(item.getPath());
                Platform.runLater(() -> textArea.setText(contents.isBlank() ? "(empty archive)" : contents));
            } catch (IOException e) {
                LOGGER.error("Could not list archive contents: {}", item.getPath(), e);
                Platform.runLater(() -> textArea.setText(I18n.t("error.archiveList", e.getMessage())));
            }
        }, "dfiles-archive-list");
        loader.setDaemon(true);
        loader.start();
    }

    private void extractArchive(Path archivePath) {
        Path destination = currentDirectory;
        Thread worker = new Thread(() -> {
            try {
                ArchiveService.extractHere(archivePath, destination);
                Platform.runLater(this::refresh);
            } catch (IOException e) {
                LOGGER.error("Could not extract archive: {}", archivePath, e);
                Platform.runLater(() ->
                        showError(I18n.t("dialog.error.title"), I18n.t("error.archiveExtract", e.getMessage())));
            }
        }, "dfiles-archive-extract");
        worker.setDaemon(true);
        worker.start();
    }

    private void showCompressDialog(Path folder) {
        List<String> formats = ArchiveService.availableCompressionFormats();
        String defaultName = folder.getFileName().toString();

        TextField nameField = new TextField(defaultName);
        ComboBox<String> formatCombo = new ComboBox<>(FXCollections.observableArrayList(formats));
        formatCombo.setValue(formats.get(0));
        Label previewLabel = new Label();
        previewLabel.getStyleClass().add("status-dim");

        Runnable updatePreview = () -> {
            String name = nameField.getText() == null ? "" : nameField.getText().trim();
            previewLabel.setText(name.isEmpty() ? "" : name + "." + formatCombo.getValue());
        };
        nameField.textProperty().addListener((o, a, b) -> updatePreview.run());
        formatCombo.valueProperty().addListener((o, a, b) -> updatePreview.run());
        updatePreview.run();

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(12);
        form.setPadding(new Insets(15));
        form.addRow(0, new Label(I18n.t("dialog.compress.name")), nameField);
        form.addRow(1, new Label(I18n.t("dialog.compress.format")), formatCombo);
        form.addRow(2, new Label(I18n.t("dialog.compress.preview")), previewLabel);
        GridPane.setHgrow(nameField, Priority.ALWAYS);
        GridPane.setHgrow(formatCombo, Priority.ALWAYS);

        Stage dialogStage = new Stage();
        Button cancelButton = new Button(I18n.t("dialog.findreplace.cancel"));
        cancelButton.setOnAction(e -> dialogStage.close());
        Button compressButton = new Button(I18n.t("dialog.compress.compress"));
        compressButton.setOnAction(e -> {
            String name = nameField.getText() == null ? "" : nameField.getText().trim();
            if (name.isEmpty()) return;
            String format = formatCombo.getValue();
            Path destination = folder.getParent().resolve(name + "." + format);
            dialogStage.close();
            Thread worker = new Thread(() -> {
                try {
                    ArchiveService.compress(folder, destination, format);
                    Platform.runLater(this::refresh);
                } catch (IOException ex) {
                    LOGGER.error("Could not compress {}", folder, ex);
                    Platform.runLater(() ->
                            showError(I18n.t("dialog.error.title"), I18n.t("error.compress", ex.getMessage())));
                }
            }, "dfiles-compress");
            worker.setDaemon(true);
            worker.start();
        });
        HBox buttonBar = new HBox(10, cancelButton, spacer(), compressButton);
        buttonBar.setAlignment(Pos.CENTER_LEFT);
        buttonBar.setPadding(new Insets(8));

        BorderPane dialogRoot = new BorderPane();
        dialogRoot.setCenter(form);
        dialogRoot.setBottom(buttonBar);

        Scene scene = new Scene(dialogRoot, 440, 230);
        scene.getStylesheets().add(getClass().getResource("/css/dfiles.css").toExternalForm());
        dialogStage.setScene(scene);
        dialogStage.setTitle(I18n.t("dialog.compress.title", folder.getFileName()));
        dialogStage.initOwner(stage);
        dialogStage.initModality(Modality.WINDOW_MODAL);
        dialogStage.show();
    }

    // ---------------- find & replace ----------------

    private void showFindReplaceDialog(FileItem item) {
        String originalContent;
        try {
            originalContent = Files.readString(item.getPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("Could not read file for find/replace: {}", item.getPath(), e);
            showError(I18n.t("dialog.error.title"), I18n.t("error.findreplace.read", e.getMessage()));
            return;
        }

        TextField findField = new TextField();
        TextField replaceField = new TextField();
        CheckBox regexCheck = new CheckBox(I18n.t("dialog.findreplace.regex"));
        CheckBox caseCheck = new CheckBox(I18n.t("dialog.findreplace.casesensitive"));
        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: #c0392b;");
        TextArea previewArea = new TextArea(originalContent);
        previewArea.setEditable(false);
        previewArea.setWrapText(false);
        previewArea.getStyleClass().add("git-output");

        Runnable updatePreview = () -> {
            String find = findField.getText();
            if (find == null || find.isEmpty()) {
                previewArea.setText(originalContent);
                errorLabel.setText("");
                return;
            }
            String replacement = replaceField.getText() == null ? "" : replaceField.getText();
            try {
                Pattern pattern = regexCheck.isSelected()
                        ? Pattern.compile(find, caseCheck.isSelected() ? 0 : Pattern.CASE_INSENSITIVE)
                        : Pattern.compile(Pattern.quote(find), caseCheck.isSelected() ? 0 : Pattern.CASE_INSENSITIVE);
                String replacementSafe = regexCheck.isSelected() ? replacement : Matcher.quoteReplacement(replacement);
                previewArea.setText(pattern.matcher(originalContent).replaceAll(replacementSafe));
                errorLabel.setText("");
            } catch (PatternSyntaxException e) {
                errorLabel.setText(I18n.t("error.findreplace.regex", e.getMessage()));
            }
        };
        findField.textProperty().addListener((o, a, b) -> updatePreview.run());
        replaceField.textProperty().addListener((o, a, b) -> updatePreview.run());
        regexCheck.selectedProperty().addListener((o, a, b) -> updatePreview.run());
        caseCheck.selectedProperty().addListener((o, a, b) -> updatePreview.run());

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        form.setPadding(new Insets(10, 10, 0, 10));
        form.addRow(0, new Label(I18n.t("dialog.findreplace.find")), findField);
        form.addRow(1, new Label(I18n.t("dialog.findreplace.replace")), replaceField);
        GridPane.setHgrow(findField, Priority.ALWAYS);
        GridPane.setHgrow(replaceField, Priority.ALWAYS);
        form.getColumnConstraints().add(new javafx.scene.layout.ColumnConstraints(110));

        HBox checksBox = new HBox(16, regexCheck, caseCheck);
        checksBox.setPadding(new Insets(4, 10, 0, 10));
        VBox topBox = new VBox(6, form, checksBox, errorLabel);
        errorLabel.setPadding(new Insets(0, 10, 4, 10));

        Stage dialogStage = new Stage();
        Button cancelButton = new Button(I18n.t("dialog.findreplace.cancel"));
        cancelButton.setOnAction(e -> dialogStage.close());
        Button saveButton = new Button(I18n.t("dialog.findreplace.save"));
        saveButton.setOnAction(e -> {
            Path versionedPath = nextVersionedPath(item.getPath());
            try {
                Files.writeString(versionedPath, previewArea.getText(), StandardCharsets.UTF_8);
                LOGGER.info("Saved find/replace result to {}", versionedPath);
                dialogStage.close();
                refresh();
            } catch (IOException ex) {
                LOGGER.error("Could not write new version {}", versionedPath, ex);
                showError(I18n.t("dialog.error.title"), I18n.t("error.findreplace.write", ex.getMessage()));
            }
        });
        HBox buttonBar = new HBox(10, cancelButton, spacer(), saveButton);
        buttonBar.setAlignment(Pos.CENTER_LEFT);
        buttonBar.setPadding(new Insets(8));

        BorderPane dialogRoot = new BorderPane();
        dialogRoot.setTop(topBox);
        dialogRoot.setCenter(previewArea);
        dialogRoot.setBottom(buttonBar);

        Scene scene = new Scene(dialogRoot, 820, 620);
        scene.getStylesheets().add(getClass().getResource("/css/dfiles.css").toExternalForm());
        dialogStage.setScene(scene);
        dialogStage.setTitle(I18n.t("dialog.findreplace.title", item.getName()));
        dialogStage.initOwner(stage);
        dialogStage.initModality(Modality.WINDOW_MODAL);
        dialogStage.show();
    }

    /** Finds the next unused "<base>_vN<ext>" filename next to the original, so Save never
     * overwrites the source file or a previously saved version. */
    private Path nextVersionedPath(Path original) {
        String fileName = original.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        Path dir = original.getParent();

        int next = 1;
        Pattern versionPattern = Pattern.compile(Pattern.quote(base) + "_v(\\d+)" + Pattern.quote(ext) + "$");
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                Matcher m = versionPattern.matcher(p.getFileName().toString());
                if (m.matches()) {
                    next = Math.max(next, Integer.parseInt(m.group(1)) + 1);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Could not scan {} for existing versions", dir, e);
        }
        return dir.resolve(base + "_v" + next + ext);
    }

    // ---------------- bookmarks ----------------

    private void addCustomFolderDialog() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.t("dialog.addFolder.title"));
        chooser.setInitialDirectory(currentDirectory.toFile());
        java.io.File selected = chooser.showDialog(stage);
        if (selected != null) {
            db.addCustomFolder(selected.getName(), selected.getAbsolutePath());
            leftPane.setBookmarks(db.listCustomFolders());
        }
    }

    // ---------------- scripts ----------------

    /** Loads a saved script's prompt, content and last output into the Script Development tab
     * and switches to it. */
    private void openScript(String name) {
        var details = db.getScriptDetails(name);
        scriptDevPane.loadScript(name, details.prompt(), details.content(), details.lastOutput(), details.hash(),
                details.dateCreated(), details.lastUpdateDate());
        centerTabs.getSelectionModel().select(scriptDevTab);
    }

    private void addScriptDialog() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle(I18n.t("dialog.newScript.title"));
        dialog.setHeaderText(I18n.t("dialog.newScript.header"));
        dialog.setContentText(I18n.t("dialog.newScript.prompt"));
        dialog.showAndWait().ifPresent(name -> {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) return;
            boolean created = db.createScript(trimmed, "#!/usr/bin/env bash\n\n");
            if (!created) {
                showError(I18n.t("dialog.error.title"), I18n.t("error.scriptExists", trimmed));
                return;
            }
            leftPane.setScripts(db.listScripts());
            openScript(trimmed);
        });
    }

    private void renameScriptDialog(String oldName) {
        TextInputDialog dialog = new TextInputDialog(oldName);
        dialog.setTitle(I18n.t("dialog.renameScript.title"));
        dialog.setHeaderText(I18n.t("dialog.renameScript.header", oldName));
        dialog.setContentText(I18n.t("dialog.renameScript.prompt"));
        dialog.showAndWait().ifPresent(newName -> {
            String trimmed = newName.trim();
            if (trimmed.isEmpty() || trimmed.equals(oldName)) return;
            boolean renamed = db.renameScript(oldName, trimmed);
            if (!renamed) {
                showError(I18n.t("dialog.error.title"), I18n.t("error.scriptExists", trimmed));
                return;
            }
            leftPane.setScripts(db.listScripts());
            if (oldName.equals(scriptDevPane.getCurrentScriptName())) {
                openScript(trimmed);
            }
        });
    }

    private void deleteScriptDialog(String name) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(I18n.t("dialog.deleteScript.title"));
        confirm.setHeaderText(I18n.t("dialog.deleteScript.header").formatted(name));
        confirm.setContentText(I18n.t("dialog.deleteScript.content"));
        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                db.deleteScript(name);
                leftPane.setScripts(db.listScripts());
                if (name.equals(scriptDevPane.getCurrentScriptName())) {
                    scriptDevPane.clearScript();
                }
            }
        });
    }

    /** Sends the AI Prompt row's text to whichever provider is selected and, on success, drops
     * the result straight into the Script row (it is not auto-saved — the user still reviews
     * and clicks Save). Runs off the FX thread since this is a network call. */
    private void runAiPrompt(String prompt) {
        AiProvider provider = scriptDevPane.getProvider();
        scriptDevPane.setPromptBusy(true);
        Thread worker = new Thread(() -> {
            try {
                String script = AiScriptService.generateScript(prompt, provider);
                Platform.runLater(() -> {
                    scriptDevPane.setScriptContent(script);
                    scriptDevPane.setPromptBusy(false);
                });
            } catch (Exception e) {
                LOGGER.error("AI script generation failed", e);
                Platform.runLater(() -> {
                    scriptDevPane.setPromptBusy(false);
                    showError(I18n.t("dialog.error.title"), I18n.t("error.aiGenerate", e.getMessage()));
                });
            }
        }, "dfiles-ai-generate");
        worker.setDaemon(true);
        worker.start();
    }

    /** Runs the Script row's current content against the currently browsed folder, streaming
     * output into the Terminal row. The output is persisted to the database as the script's
     * "last output" as soon as the run finishes, independent of the explicit Save action. */
    private void runCurrentScript() {
        String content = scriptDevPane.getScriptContent();
        if (content == null || content.isBlank()) return;
        String name = scriptDevPane.getCurrentScriptName();
        scriptDevPane.clearTerminal();
        scriptDevPane.setScriptRunning(true);
        Path workDir = currentDirectory != null ? currentDirectory : Paths.get(System.getProperty("user.home"));
        ScriptRunner.run(content, workDir, new ScriptRunner.Listener() {
            @Override
            public void onLine(String line) {
                scriptDevPane.appendTerminalLine(line);
            }

            @Override
            public void onFinished(int exitCode) {
                scriptDevPane.appendTerminalLine(I18n.t("status.scriptFinished", exitCode));
                scriptDevPane.setScriptRunning(false);
                if (name != null) db.saveScriptOutput(name, scriptDevPane.getTerminalText());
            }

            @Override
            public void onError(Exception e) {
                scriptDevPane.appendTerminalLine("Error: " + e.getMessage());
                scriptDevPane.setScriptRunning(false);
                if (name != null) db.saveScriptOutput(name, scriptDevPane.getTerminalText());
            }
        });
    }

    /** Persists the prompt and script content shown in the Script Development tab, and refreshes
     * the displayed content hash and last-updated date to match what was just saved. */
    private void saveCurrentScript() {
        String name = scriptDevPane.getCurrentScriptName();
        if (name == null) return;
        var result = db.saveScript(name, scriptDevPane.getPromptText(), scriptDevPane.getScriptContent());
        scriptDevPane.setScriptHash(result.hash());
        scriptDevPane.setLastUpdateDate(result.updatedAt());
        statusLabel.setText(I18n.t("status.scriptSaved", name));
        javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(2));
        pause.setOnFinished(e -> updateStatusBar());
        pause.play();
    }

    // ---------------- git ----------------

    private void updateGitControls() {
        gitMenu.setDisable(!git.isGitRepo(currentDirectory));
    }

    private void showGitResult(GitService.GitResult result) {
        gitOutputArea.setText(result.output == null || result.output.isBlank()
                ? (result.success ? I18n.t("git.noChanges") : I18n.t("git.notARepo"))
                : result.output);
        gitOutputPanel.setVisible(true);
    }

    private void commitDialog() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle(I18n.t("dialog.commit.title"));
        dialog.setHeaderText(I18n.t("dialog.commit.header").formatted(currentDirectory.getFileName()));
        dialog.setContentText(I18n.t("dialog.commit.prompt"));
        dialog.showAndWait().ifPresent(msg -> {
            if (msg.isBlank()) return;
            git.commitAll(currentDirectory, msg, this::showGitResult);
        });
    }

    // ---------------- locale switching ----------------

    private void switchLocale(Locale locale) {
        I18n.setLocale(locale);
        db.setSetting("locale", locale.getLanguage());
        rebuildUiTexts();
    }

    private void rebuildUiTexts() {
        stage.setTitle(I18n.t("app.title"));
        leftPane.applyLabels();
        filesPane.applyLabels();
        scriptDevPane.applyLabels();
        filesTab.setText(I18n.t("tabs.files"));
        scriptDevTab.setText(I18n.t("tabs.scriptDev"));
        toggleSidebarBtn.setTooltip(new Tooltip(I18n.t("toolbar.toggleSidebar")));
        backBtn.setTooltip(new Tooltip(I18n.t("toolbar.back")));
        forwardBtn.setTooltip(new Tooltip(I18n.t("toolbar.forward")));
        upBtn.setTooltip(new Tooltip(I18n.t("toolbar.up")));
        refreshBtn.setTooltip(new Tooltip(I18n.t("toolbar.refresh")));
        newFolderBtn.setTooltip(new Tooltip(I18n.t("toolbar.newFolder")));
        newFileBtn.setTooltip(new Tooltip(I18n.t("toolbar.newFile")));
        deleteBtn.setTooltip(new Tooltip(I18n.t("toolbar.delete")));
        terminalBtn.setTooltip(new Tooltip(I18n.t("toolbar.terminal")));
        copyListBtn.setTooltip(new Tooltip(I18n.t("toolbar.copyList")));
        showHiddenToggle.setTooltip(new Tooltip(I18n.t("toolbar.showHidden")));
        gitMenu.setText(I18n.t("toolbar.git"));
        helpBtn.setTooltip(new Tooltip(I18n.t("toolbar.help")));
        aboutBtn.setTooltip(new Tooltip(I18n.t("menu.about")));
        closeBtn.setTooltip(new Tooltip(I18n.t("toolbar.close")));
        searchField.setPromptText(I18n.t("toolbar.search"));
        if (watchingActive) watchingLabel.setText(I18n.t("status.watching"));
        updateStatusBar();
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setContentText(message);
        alert.showAndWait();
    }

    public void shutdown() {
        watcher.stop();
        scanner.shutdown();
        git.shutdown();
    }

    /** Single shutdown path used by both the window's own close button and our in-app Close
     * button, so window size is saved and background threads stop the same way either time. */
    public void requestExit() {
        LOGGER.info("DFiles exiting");
        db.setSetting("window_width", String.valueOf(stage.getWidth()));
        db.setSetting("window_height", String.valueOf(stage.getHeight()));
        shutdown();
        db.close();
        Platform.exit();
    }
}

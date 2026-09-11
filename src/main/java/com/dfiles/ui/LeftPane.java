package com.dfiles.ui;

import com.dfiles.i18n.I18n;
import com.dfiles.model.CustomFolder;
import com.dfiles.model.PlaceEntry;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Left-hand navigation panel: standard "places" plus user-defined bookmarked folders. */
public class LeftPane {

    private static final Logger LOGGER = LogManager.getLogger(LeftPane.class);

    private final VBox root = new VBox();
    private final ListView<PlaceEntry> placesList = new ListView<>();
    private final ListView<PlaceEntry> bookmarksList = new ListView<>();
    private final Label placesHeader = new Label();
    private final Label bookmarksHeader = new Label();
    private final Button addFolderButton = new Button();

    private Consumer<Path> onNavigate;
    private Runnable onAddFolder;
    private IntConsumer onRemoveBookmark;
    private Consumer<Path> onShowProperties;

    public LeftPane() {
        root.setPrefWidth(220);
        root.getStyleClass().add("left-pane");

        styleHeader(placesHeader);
        styleHeader(bookmarksHeader);

        placesList.setFocusTraversable(false);
        placesList.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        placesList.setCellFactory(lv -> placeCell(false));
        placesList.getStyleClass().add("places-list");
        VBox.setVgrow(placesList, Priority.ALWAYS);

        bookmarksList.setFocusTraversable(false);
        bookmarksList.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        bookmarksList.setCellFactory(lv -> placeCell(true));
        VBox.setVgrow(bookmarksList, Priority.ALWAYS);
        bookmarksList.getStyleClass().add("bookmarks-list");

        addFolderButton.setMaxWidth(Double.MAX_VALUE);
        addFolderButton.setOnAction(e -> { if (onAddFolder != null) onAddFolder.run(); });

        VBox placesSection = new VBox(placesHeader, placesList);
        VBox bookmarksSection = new VBox(bookmarksHeader, bookmarksList);

        // Draggable divider between Places and Bookmarks so the user can resize
        // how much vertical space each section gets.
        SplitPane sectionsSplit = new SplitPane(placesSection, bookmarksSection);
        sectionsSplit.setOrientation(Orientation.VERTICAL);
        sectionsSplit.setDividerPositions(0.5);
        VBox.setVgrow(sectionsSplit, Priority.ALWAYS);

        root.setPadding(new Insets(8));
        root.setSpacing(4);
        root.getChildren().addAll(sectionsSplit, addFolderButton);

        placesList.setItems(buildStandardPlaces());
        applyLabels();
    }

    private void styleHeader(Label label) {
        label.setFont(Font.font(label.getFont().getFamily(), FontWeight.BOLD, 11));
        label.setPadding(new Insets(6, 4, 2, 4));
        label.getStyleClass().add("section-header");
    }

    public void applyLabels() {
        placesHeader.setText(I18n.t("places.header").toUpperCase());
        bookmarksHeader.setText(I18n.t("places.bookmarksHeader").toUpperCase());
        addFolderButton.setText(I18n.t("places.addFolder"));
        placesList.setItems(buildStandardPlaces());
        placesList.refresh();
        bookmarksList.refresh();
    }

    private ObservableList<PlaceEntry> buildStandardPlaces() {
        String home = System.getProperty("user.home");
        ObservableList<PlaceEntry> items = FXCollections.observableArrayList();
        items.add(PlaceEntry.standard(I18n.t("places.home"), Paths.get(home), "🏠"));
        addIfExists(items, I18n.t("places.desktop"), home, "Desktop", "🖥");
        addIfExists(items, I18n.t("places.documents"), home, "Documents", "🗀");
        addIfExists(items, I18n.t("places.downloads"), home, "Downloads", "⬇");
        addIfExists(items, I18n.t("places.music"), home, "Music", "♪");
        addIfExists(items, I18n.t("places.pictures"), home, "Pictures", "🖼");
        addIfExists(items, I18n.t("places.videos"), home, "Videos", "🎬");
        items.add(PlaceEntry.root(I18n.t("places.filesystem"), Paths.get("/")));
        return items;
    }

    /** Standard folders (Desktop, Downloads, ...) are created on demand, matching how a
     * desktop environment normally provisions them, so they always show up in Places
     * even on a fresh/minimal account that never had them. */
    private void addIfExists(ObservableList<PlaceEntry> items, String label, String home, String subdir, String icon) {
        Path p = Paths.get(home, subdir);
        if (!Files.isDirectory(p)) {
            try {
                Files.createDirectories(p);
            } catch (IOException e) {
                LOGGER.warn("Could not create standard folder {}", p, e);
                return;
            }
        }
        items.add(PlaceEntry.standard(label, p, icon));
    }

    private ListCell<PlaceEntry> placeCell(boolean removable) {
        ListCell<PlaceEntry> cell = new ListCell<>() {
            @Override
            protected void updateItem(PlaceEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setContextMenu(null);
                } else {
                    Label icon = new Label(item.getIcon());
                    icon.getStyleClass().add("icon-glyph");
                    Label name = new Label(item.getLabel());
                    HBox box = new HBox(6, icon, name);
                    box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                    setText(null);
                    setGraphic(box);

                    ContextMenu menu = new ContextMenu();
                    if (removable) {
                        MenuItem remove = new MenuItem(I18n.t("places.removeFolder"));
                        remove.setOnAction(e -> {
                            if (onRemoveBookmark != null && item.getCustomFolderId() != null) {
                                onRemoveBookmark.accept(item.getCustomFolderId());
                            }
                        });
                        menu.getItems().add(remove);
                    }
                    MenuItem properties = new MenuItem(I18n.t("context.properties"));
                    properties.setOnAction(e -> {
                        if (onShowProperties != null) onShowProperties.accept(item.getPath());
                    });
                    menu.getItems().add(properties);
                    setContextMenu(menu);
                }
            }
        };
        cell.setOnMouseClicked(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY && !cell.isEmpty() && onNavigate != null) {
                onNavigate.accept(cell.getItem().getPath());
            }
        });
        return cell;
    }

    public void setBookmarks(List<CustomFolder> folders) {
        ObservableList<PlaceEntry> items = FXCollections.observableArrayList();
        for (CustomFolder cf : folders) {
            items.add(PlaceEntry.custom(cf.getName(), Paths.get(cf.getPath()), cf.getId()));
        }
        bookmarksList.setItems(items);
    }

    public void selectPath(Path path) {
        for (PlaceEntry pe : placesList.getItems()) {
            if (pe.getPath().equals(path)) {
                placesList.getSelectionModel().select(pe);
                bookmarksList.getSelectionModel().clearSelection();
                return;
            }
        }
        for (PlaceEntry pe : bookmarksList.getItems()) {
            if (pe.getPath().equals(path)) {
                bookmarksList.getSelectionModel().select(pe);
                placesList.getSelectionModel().clearSelection();
                return;
            }
        }
        placesList.getSelectionModel().clearSelection();
        bookmarksList.getSelectionModel().clearSelection();
    }

    public VBox getNode() { return root; }
    public void setOnNavigate(Consumer<Path> onNavigate) { this.onNavigate = onNavigate; }
    public void setOnAddFolder(Runnable onAddFolder) { this.onAddFolder = onAddFolder; }
    public void setOnRemoveBookmark(IntConsumer onRemoveBookmark) { this.onRemoveBookmark = onRemoveBookmark; }
    public void setOnShowProperties(Consumer<Path> onShowProperties) { this.onShowProperties = onShowProperties; }
}

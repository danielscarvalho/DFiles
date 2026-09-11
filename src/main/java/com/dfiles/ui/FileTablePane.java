package com.dfiles.ui;

import com.dfiles.i18n.I18n;
import com.dfiles.model.FileItem;
import com.dfiles.service.FileTypeUtil;
import com.dfiles.util.FileSizeFormatter;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.util.Callback;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** Right-hand panel: sortable table of the current folder's contents. */
public class FileTablePane {

    private final TableView<FileItem> table = new TableView<>();
    private final TableColumn<FileItem, FileItem> nameCol = new TableColumn<>();
    private final TableColumn<FileItem, FileItem> sizeCol = new TableColumn<>();
    private final TableColumn<FileItem, FileItem> typeCol = new TableColumn<>();
    private final TableColumn<FileItem, FileItem> modifiedCol = new TableColumn<>();

    private Consumer<FileItem> onOpen;
    private Consumer<List<FileItem>> onSelectionChanged;
    private Callback<FileItem, javafx.scene.control.ContextMenu> contextMenuFactory;

    @SuppressWarnings("unchecked")
    public FileTablePane() {
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setPlaceholder(new Label());
        table.getStyleClass().add("file-table");

        identity(nameCol);
        identity(sizeCol);
        identity(typeCol);
        identity(modifiedCol);

        nameCol.setCellFactory(col -> nameCell());
        nameCol.setComparator((a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        nameCol.setPrefWidth(320);

        sizeCol.setCellFactory(col -> plainCell(item -> FileSizeFormatter.formatSize(item.getSize(), item.isDirectory())));
        sizeCol.setComparator((a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return Long.compare(a.getSize(), b.getSize());
        });
        sizeCol.setPrefWidth(100);
        sizeCol.setStyle("-fx-alignment: CENTER-RIGHT;");

        typeCol.setCellFactory(col -> plainCell(FileItem::getTypeLabel));
        typeCol.setComparator((a, b) -> a.getTypeLabel().compareToIgnoreCase(b.getTypeLabel()));
        typeCol.setPrefWidth(150);

        modifiedCol.setCellFactory(col -> plainCell(item -> FileSizeFormatter.formatDate(item.getLastModified())));
        modifiedCol.setComparator((a, b) -> Long.compare(a.getLastModified(), b.getLastModified()));
        modifiedCol.setPrefWidth(160);

        table.getColumns().addAll(List.of(nameCol, sizeCol, typeCol, modifiedCol));
        table.setRowFactory(this::buildRow);
        table.setOnContextMenuRequested(e -> {
            // Single source of truth for context menus: figure out which row (if any) was
            // under the click, select it, then ask for the right menu (item vs. empty-space).
            // Doing this here rather than per-row avoids a race with the platform's
            // synthesized ContextMenuEvent, which can fire before a row's own mouse handler
            // has a chance to update Node.contextMenu.
            javafx.scene.Node node = e.getPickResult().getIntersectedNode();
            while (node != null && !(node instanceof TableRow)) node = node.getParent();
            TableRow<?> row = (node instanceof TableRow<?> r) ? r : null;

            FileItem item = null;
            if (row != null && !row.isEmpty()) {
                item = (FileItem) row.getItem();
                if (!table.getSelectionModel().getSelectedItems().contains(item)) {
                    table.getSelectionModel().clearAndSelect(row.getIndex());
                }
            }
            if (contextMenuFactory != null) {
                javafx.scene.control.ContextMenu menu = contextMenuFactory.call(item);
                if (menu != null) menu.show(table, e.getScreenX(), e.getScreenY());
            }
        });

        table.getSelectionModel().getSelectedItems().addListener((javafx.collections.ListChangeListener<FileItem>) c -> {
            if (onSelectionChanged != null) {
                onSelectionChanged.accept(List.copyOf(table.getSelectionModel().getSelectedItems()));
            }
        });

        applyLabels();
    }

    private void identity(TableColumn<FileItem, FileItem> col) {
        col.setCellValueFactory(data -> new javafx.beans.property.SimpleObjectProperty<>(data.getValue()));
        col.setSortable(true);
    }

    public void applyLabels() {
        nameCol.setText(I18n.t("table.name"));
        sizeCol.setText(I18n.t("table.size"));
        typeCol.setText(I18n.t("table.type"));
        modifiedCol.setText(I18n.t("table.modified"));
    }

    private TableCell<FileItem, FileItem> nameCell() {
        return new TableCell<>() {
            @Override
            protected void updateItem(FileItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    Label icon = new Label(FileTypeUtil.icon(item.getPath(), item.isDirectory()));
                    icon.getStyleClass().add("icon-glyph");
                    Label name = new Label(item.getName());
                    HBox box = new HBox(6, icon, name);
                    box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                    setText(null);
                    setGraphic(box);
                    setOpacity(item.isHidden() ? 0.55 : 1.0);
                }
            }
        };
    }

    private TableCell<FileItem, FileItem> plainCell(java.util.function.Function<FileItem, String> formatter) {
        return new TableCell<>() {
            @Override
            protected void updateItem(FileItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(formatter.apply(item));
                    setOpacity(item.isHidden() ? 0.55 : 1.0);
                }
            }
        };
    }

    private TableRow<FileItem> buildRow(TableView<FileItem> tv) {
        TableRow<FileItem> row = new TableRow<>();
        row.setOnMouseClicked(e -> {
            if (row.isEmpty()) return;
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && onOpen != null) {
                onOpen.accept(row.getItem());
            }
        });
        return row;
    }

    public void setItems(List<FileItem> items) {
        ObservableList<FileItem> observable = FXCollections.observableArrayList(items);
        table.setItems(observable);
    }

    public void selectByPaths(List<Path> paths) {
        table.getSelectionModel().clearSelection();
        for (FileItem item : table.getItems()) {
            if (paths.contains(item.getPath())) {
                table.getSelectionModel().select(item);
            }
        }
    }

    public List<FileItem> getSelectedItems() {
        return List.copyOf(table.getSelectionModel().getSelectedItems());
    }

    public TableView<FileItem> getTableView() { return table; }
    public void setOnOpen(Consumer<FileItem> onOpen) { this.onOpen = onOpen; }
    public void setOnSelectionChanged(Consumer<List<FileItem>> c) { this.onSelectionChanged = c; }
    public void setContextMenuFactory(Callback<FileItem, javafx.scene.control.ContextMenu> factory) {
        this.contextMenuFactory = factory;
    }
}

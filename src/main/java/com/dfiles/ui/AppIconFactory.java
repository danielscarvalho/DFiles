package com.dfiles.ui;

import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Label;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

/**
 * Builds the application's window/taskbar icon from exactly two Unicode characters — a folder
 * glyph and a bold Greek capital Delta (Δ), standing in for "D" — rendered with JavaFX rather
 * than shipped as a static image file.
 *
 * The folder glyph is 🗀 (U+1F5C0 FOLDER), not the more common 📁 (U+1F4C1 FILE FOLDER):
 * JavaFX's renderer cannot draw glyphs from color/bitmap emoji fonts at all (a font-resolution
 * issue discovered and fixed elsewhere in this codebase — see FileTypeUtil), and 📁 resolves to
 * one on this platform while 🗀 reliably resolves to a plain monochrome symbol font instead.
 */
public final class AppIconFactory {

    private static final String FOLDER_GLYPH = "🗀";
    private static final String LETTER = "Δ"; // Greek capital Delta, the letter that transliterates to "D"
    private static final String BACKGROUND_COLOR = "#7a1010";

    private AppIconFactory() {}

    public static WritableImage create(int size) {
        Rectangle background = new Rectangle(size, size, Color.web(BACKGROUND_COLOR));
        double arc = size * 0.18;
        background.setArcWidth(arc);
        background.setArcHeight(arc);

        Label folder = new Label(FOLDER_GLYPH);
        folder.setStyle(String.format(
                "-fx-font-family: 'Noto Sans Symbols2','Segoe UI Symbol','Apple Symbols',sans-serif;"
                        + " -fx-font-size: %.1fpx; -fx-text-fill: white;",
                size * 0.74));

        Label letter = new Label(LETTER);
        letter.setStyle(String.format(
                "-fx-font-family: 'Arial','Helvetica',sans-serif; -fx-font-weight: 900;"
                        + " -fx-font-size: %.1fpx; -fx-text-fill: black;",
                size * 0.42));
        letter.setTranslateY(size * 0.04);

        StackPane root = new StackPane(background, folder, letter);
        root.setAlignment(Pos.CENTER);
        // Force the exact pixel size: a Label's font metrics (ascent/descent) can make it want
        // more space than its point size suggests, which would otherwise inflate the StackPane
        // past size x size and throw off where the snapshot's (0,0) origin lands.
        root.setMinSize(size, size);
        root.setMaxSize(size, size);
        root.setPrefSize(size, size);
        root.setStyle("-fx-background-color: transparent;");
        root.setClip(new Rectangle(size, size));

        new Scene(root); // a live Scene is required for CSS/layout to resolve before snapshotting
        root.applyCss();
        root.layout();

        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        return root.snapshot(params, new WritableImage(size, size));
    }
}

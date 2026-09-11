package com.dfiles;

import com.dfiles.db.Database;
import com.dfiles.i18n.I18n;
import com.dfiles.ui.MainController;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Locale;

public class MainApp extends Application {

    private static final Logger LOGGER = LogManager.getLogger(MainApp.class);

    private Database db;
    private MainController controller;

    @Override
    public void start(Stage stage) throws Exception {
        LOGGER.info("DFiles starting up");
        db = new Database();

        String savedLocale = db.getSetting("locale", null);
        Locale locale = savedLocale != null ? I18n.fromTag(savedLocale) : I18n.detectInitialLocale();
        I18n.setLocale(locale);

        controller = new MainController(stage, db);
        BorderPane root = controller.buildUI();

        double width = Double.parseDouble(db.getSetting("window_width", "1100"));
        double height = Double.parseDouble(db.getSetting("window_height", "700"));

        Scene scene = new Scene(root, width, height);
        scene.getStylesheets().add(getClass().getResource("/css/dfiles.css").toExternalForm());

        stage.setTitle(I18n.t("app.title"));
        stage.getIcons().add(new Image(getClass().getResourceAsStream("/icons/DFile-Logo.png")));
        stage.setScene(scene);
        stage.show();

        stage.setOnCloseRequest(e -> controller.requestExit());
    }

    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> LOGGER.error("Uncaught exception on thread {}", t.getName(), e));
        launch(args);
    }
}

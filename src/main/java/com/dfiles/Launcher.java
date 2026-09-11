package com.dfiles;

/**
 * Separate entry point (does not extend javafx.application.Application) so the app
 * can be run as a plain runnable jar without the JVM's "JavaFX runtime components
 * are missing" launcher check, which only inspects the declared main class.
 */
public class Launcher {
    public static void main(String[] args) {
        MainApp.main(args);
    }
}

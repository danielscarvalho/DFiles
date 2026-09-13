package com.dfiles.db;

import com.dfiles.model.CustomFolder;
import com.dfiles.model.FileItem;
import com.dfiles.model.ScriptEntry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns the .dfiles/dfiles.sqlite file used to persist bookmarks, app settings,
 * per-folder view preferences and a filesystem cache that lets folders paint
 * instantly on repeat visits (the real listing is refreshed right after, in the background).
 */
public class Database {

    private static final Logger LOGGER = LogManager.getLogger(Database.class);
    private static final String DB_DIR_NAME = ".dfiles";
    private static final String DB_FILE_NAME = "dfiles.sqlite";

    private final Connection connection;

    public Database() throws SQLException, IOException {
        Path dbDir = Paths.get(System.getProperty("user.home"), DB_DIR_NAME);
        Files.createDirectories(dbDir);
        Path dbFile = dbDir.resolve(DB_FILE_NAME);
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
        initSchema();
    }

    private void initSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS custom_folders (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    path TEXT NOT NULL UNIQUE
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS settings (
                    key TEXT PRIMARY KEY,
                    value TEXT
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS file_cache (
                    folder_path TEXT NOT NULL,
                    name TEXT NOT NULL,
                    full_path TEXT NOT NULL,
                    is_directory INTEGER NOT NULL,
                    size INTEGER NOT NULL,
                    last_modified INTEGER NOT NULL,
                    type_label TEXT,
                    hidden INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (folder_path, name)
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS folder_prefs (
                    folder_path TEXT PRIMARY KEY,
                    sort_column TEXT,
                    sort_ascending INTEGER DEFAULT 1,
                    show_hidden INTEGER DEFAULT 0
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS scripts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE,
                    content TEXT NOT NULL DEFAULT '',
                    updated_at INTEGER NOT NULL
                )
            """);
        }
    }

    // ---------------- settings ----------------

    public synchronized String getSetting(String key, String defaultValue) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT value FROM settings WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("value");
            }
        } catch (SQLException e) {
            LOGGER.error("Database operation failed", e);
        }
        return defaultValue;
    }

    public synchronized void setSetting(String key, String value) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO settings(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Database operation failed", e);
        }
    }

    // ---------------- custom folders ----------------

    public synchronized List<CustomFolder> listCustomFolders() {
        List<CustomFolder> result = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, name, path FROM custom_folders ORDER BY name COLLATE NOCASE")) {
            while (rs.next()) {
                result.add(new CustomFolder(rs.getInt("id"), rs.getString("name"), rs.getString("path")));
            }
        } catch (SQLException e) {
            LOGGER.error("Database operation failed", e);
        }
        return result;
    }

    public synchronized void addCustomFolder(String name, String path) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO custom_folders(name, path) VALUES (?, ?)")) {
            ps.setString(1, name);
            ps.setString(2, path);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Database operation failed", e);
        }
    }

    public synchronized void removeCustomFolder(int id) {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM custom_folders WHERE id = ?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Database operation failed", e);
        }
    }

    // ---------------- file cache (fast paint on revisit) ----------------

    public synchronized List<FileItem> loadCachedFolder(String folderPath) {
        List<FileItem> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT name, full_path, is_directory, size, last_modified, type_label, hidden FROM file_cache WHERE folder_path = ?")) {
            ps.setString(1, folderPath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new FileItem(
                            rs.getString("name"),
                            Path.of(rs.getString("full_path")),
                            rs.getInt("is_directory") == 1,
                            rs.getLong("size"),
                            rs.getLong("last_modified"),
                            rs.getString("type_label"),
                            rs.getInt("hidden") == 1
                    ));
                }
            }
        } catch (SQLException e) {
            LOGGER.error("Database operation failed", e);
        }
        return result;
    }

    public synchronized void replaceCachedFolder(String folderPath, List<FileItem> items) {
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement del = connection.prepareStatement("DELETE FROM file_cache WHERE folder_path = ?")) {
                del.setString(1, folderPath);
                del.executeUpdate();
            }
            try (PreparedStatement ins = connection.prepareStatement(
                    "INSERT INTO file_cache(folder_path, name, full_path, is_directory, size, last_modified, type_label, hidden) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (FileItem item : items) {
                    ins.setString(1, folderPath);
                    ins.setString(2, item.getName());
                    ins.setString(3, item.getPath().toString());
                    ins.setInt(4, item.isDirectory() ? 1 : 0);
                    ins.setLong(5, item.getSize());
                    ins.setLong(6, item.getLastModified());
                    ins.setString(7, item.getTypeLabel());
                    ins.setInt(8, item.isHidden() ? 1 : 0);
                    ins.addBatch();
                }
                ins.executeBatch();
            }
            connection.commit();
        } catch (SQLException e) {
            try { connection.rollback(); } catch (SQLException ignored) {}
            LOGGER.error("Database operation failed", e);
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    // ---------------- per-folder view preferences ----------------

    public synchronized String[] getFolderPrefs(String folderPath) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT sort_column, sort_ascending, show_hidden FROM folder_prefs WHERE folder_path = ?")) {
            ps.setString(1, folderPath);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new String[]{
                            rs.getString("sort_column"),
                            String.valueOf(rs.getInt("sort_ascending")),
                            String.valueOf(rs.getInt("show_hidden"))
                    };
                }
            }
        } catch (SQLException e) {
            LOGGER.error("Database operation failed", e);
        }
        return null;
    }

    public synchronized void saveFolderPrefs(String folderPath, String sortColumn, boolean ascending, boolean showHidden) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO folder_prefs(folder_path, sort_column, sort_ascending, show_hidden) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT(folder_path) DO UPDATE SET sort_column = excluded.sort_column, " +
                "sort_ascending = excluded.sort_ascending, show_hidden = excluded.show_hidden")) {
            ps.setString(1, folderPath);
            ps.setString(2, sortColumn);
            ps.setInt(3, ascending ? 1 : 0);
            ps.setInt(4, showHidden ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Database operation failed", e);
        }
    }

    // ---------------- scripts ----------------

    public synchronized List<ScriptEntry> listScripts() {
        List<ScriptEntry> result = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, name FROM scripts ORDER BY name COLLATE NOCASE")) {
            while (rs.next()) {
                result.add(new ScriptEntry(rs.getInt("id"), rs.getString("name")));
            }
        } catch (SQLException e) {
            LOGGER.error("Database operation failed", e);
        }
        return result;
    }

    public synchronized String getScriptContent(String name) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT content FROM scripts WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("content");
            }
        } catch (SQLException e) {
            LOGGER.error("Database operation failed", e);
        }
        return "";
    }

    /** Creates the script if the name is new, or returns false if it already exists. */
    public synchronized boolean createScript(String name, String content) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO scripts(name, content, updated_at) VALUES (?, ?, ?)")) {
            ps.setString(1, name);
            ps.setString(2, content);
            ps.setLong(3, System.currentTimeMillis());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOGGER.error("Database operation failed", e);
            return false;
        }
    }

    public synchronized void saveScriptContent(String name, String content) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE scripts SET content = ?, updated_at = ? WHERE name = ?")) {
            ps.setString(1, content);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, name);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Database operation failed", e);
        }
    }

    /** Returns false if newName is already taken by another script. */
    public synchronized boolean renameScript(String oldName, String newName) {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE scripts SET name = ? WHERE name = ?")) {
            ps.setString(1, newName);
            ps.setString(2, oldName);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            LOGGER.error("Database operation failed", e);
            return false;
        }
    }

    public synchronized void deleteScript(String name) {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM scripts WHERE name = ?")) {
            ps.setString(1, name);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Database operation failed", e);
        }
    }

    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            LOGGER.error("Database operation failed", e);
        }
    }
}

package com.dfiles.db;

import com.dfiles.model.CustomFolder;
import com.dfiles.model.FileItem;
import com.dfiles.model.ScriptDetails;
import com.dfiles.model.ScriptEntry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Owns the {@code ~/.dfiles/dfiles.sqlite} file used to persist bookmarks, app settings,
 * per-folder view preferences, saved AI-assisted bash scripts, and a filesystem cache that lets
 * folders paint instantly on repeat visits (the real listing is refreshed right after, in the
 * background).
 *
 * <p>All public methods are {@code synchronized}: the single underlying {@link Connection} is
 * shared by the JavaFX Application Thread and the various background worker threads this app
 * uses for scanning, git, and script execution, and SQLite connections are not safe for
 * concurrent use from multiple threads without external locking.
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
                    prompt TEXT NOT NULL DEFAULT '',
                    content TEXT NOT NULL DEFAULT '',
                    last_output TEXT NOT NULL DEFAULT '',
                    hash TEXT NOT NULL DEFAULT '',
                    updated_at INTEGER NOT NULL
                )
            """);
        }
        // Migration path for databases created before prompt/last_output/hash existed.
        ensureColumn("scripts", "prompt", "TEXT NOT NULL DEFAULT ''");
        ensureColumn("scripts", "last_output", "TEXT NOT NULL DEFAULT ''");
        ensureColumn("scripts", "hash", "TEXT NOT NULL DEFAULT ''");
        backfillScriptHashes();
    }

    /** One-time backfill for rows left over from before the {@code hash} column existed, so
     * scripts saved in an older version of DFiles get a hash without needing to be re-saved. */
    private void backfillScriptHashes() throws SQLException {
        try (Statement select = connection.createStatement();
             ResultSet rs = select.executeQuery("SELECT id, content FROM scripts WHERE hash = ''")) {
            try (PreparedStatement update = connection.prepareStatement("UPDATE scripts SET hash = ? WHERE id = ?")) {
                while (rs.next()) {
                    update.setString(1, sha256Hex(rs.getString("content")));
                    update.setInt(2, rs.getInt("id"));
                    update.addBatch();
                }
                update.executeBatch();
            }
        }
    }

    /** Hex-encoded SHA-256 digest of {@code content}, used as a script's content fingerprint —
     * it changes whenever the script's text changes, so it can be used to tell two saved
     * versions of a script apart or confirm a script matches what was originally generated. */
    private static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a required MessageDigest algorithm", e);
        }
    }

    /** Adds {@code column} to {@code table} if it isn't already there — a lightweight, additive
     * migration mechanism so older .dfiles/dfiles.sqlite files pick up new columns in place. */
    private void ensureColumn(String table, String column, String columnDefinitionSql) throws SQLException {
        boolean exists = false;
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    exists = true;
                    break;
                }
            }
        }
        if (!exists) {
            try (Statement st = connection.createStatement()) {
                st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + columnDefinitionSql);
                LOGGER.info("Migrated database: added column {}.{}", table, column);
            }
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

    /** Returns the prompt, content, last run output and content hash saved for {@code name}, or
     * empty strings if the script doesn't exist (which callers can treat the same as "nothing
     * saved yet"). */
    public synchronized ScriptDetails getScriptDetails(String name) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT prompt, content, last_output, hash FROM scripts WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new ScriptDetails(rs.getString("prompt"), rs.getString("content"),
                            rs.getString("last_output"), rs.getString("hash"));
                }
            }
        } catch (SQLException e) {
            LOGGER.error("Database operation failed", e);
        }
        return new ScriptDetails("", "", "", "");
    }

    /** Creates the script if the name is new, or returns false if it already exists. */
    public synchronized boolean createScript(String name, String content) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO scripts(name, content, hash, updated_at) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, name);
            ps.setString(2, content);
            ps.setString(3, sha256Hex(content));
            ps.setLong(4, System.currentTimeMillis());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOGGER.error("Database operation failed", e);
            return false;
        }
    }

    /** Saves the prompt and script content together — used by the Script Development tab's
     * Save button, which commits both fields in one step — and recomputes the content hash.
     * Returns the new hash so the caller can refresh what it displays without a second query. */
    public synchronized String saveScript(String name, String prompt, String content) {
        String hash = sha256Hex(content);
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE scripts SET prompt = ?, content = ?, hash = ?, updated_at = ? WHERE name = ?")) {
            ps.setString(1, prompt);
            ps.setString(2, content);
            ps.setString(3, hash);
            ps.setLong(4, System.currentTimeMillis());
            ps.setString(5, name);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Database operation failed", e);
        }
        return hash;
    }

    /** Records the output from the most recent test run of {@code name}, independent of saving
     * the script itself (so re-running a script updates its saved output without requiring an
     * explicit Save). */
    public synchronized void saveScriptOutput(String name, String output) {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE scripts SET last_output = ? WHERE name = ?")) {
            ps.setString(1, output);
            ps.setString(2, name);
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

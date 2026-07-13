package com.chargedserver.database;

import com.chargedserver.ChargedServerPlugin;
import lombok.Getter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class DatabaseManager {

    public enum Backend { MYSQL, SQLITE, YAML }

    private final ChargedServerPlugin plugin;
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Charged-Database");
        thread.setDaemon(true);
        return thread;
    });

    @Getter private volatile Backend backend = Backend.YAML;
    private Connection connection;
    private File yamlFile;
    private YamlConfiguration yaml;
    private final String playersTable;
    private final String linksTable;

    public DatabaseManager(ChargedServerPlugin plugin) {
        this.plugin = plugin;
        String prefix = plugin.getConfig().getString("storage.table-prefix", "charged_");
        this.playersTable = prefix + "players";
        this.linksTable = prefix + "links";
    }

    public CompletableFuture<Void> init() {
        return CompletableFuture.runAsync(this::connect, dbExecutor);
    }

    private void connect() {
        FileConfiguration cfg = plugin.getConfig();
        if (cfg.getBoolean("storage.mysql.enabled", false)
                && hasDriver("com.mysql.cj.jdbc.Driver", "com.mysql.jdbc.Driver", "org.mariadb.jdbc.Driver")) {
            try {
                String url = "jdbc:mysql://" + cfg.getString("storage.mysql.host", "localhost")
                        + ":" + cfg.getInt("storage.mysql.port", 3306)
                        + "/" + cfg.getString("storage.mysql.database", "charged")
                        + "?useSSL=false&autoReconnect=true";
                connection = DriverManager.getConnection(url,
                        cfg.getString("storage.mysql.username", "root"),
                        cfg.getString("storage.mysql.password", ""));
                backend = Backend.MYSQL;
            } catch (SQLException e) {
                plugin.getLogger().warning("MySQL connection failed, falling back: " + e.getMessage());
            }
        }
        if (connection == null && hasDriver("org.sqlite.JDBC")) {
            try {
                plugin.getDataFolder().mkdirs();
                String fileName = cfg.getString("storage.sqlite.file", "charged.db");
                File dbFile = new File(plugin.getDataFolder(), fileName);
                connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
                backend = Backend.SQLITE;
            } catch (SQLException e) {
                plugin.getLogger().warning("SQLite connection failed, falling back: " + e.getMessage());
            }
        }
        if (connection != null) {
            createTables();
        } else {
            backend = Backend.YAML;
            plugin.getDataFolder().mkdirs();
            yamlFile = new File(plugin.getDataFolder(), "data.yml");
            yaml = YamlConfiguration.loadConfiguration(yamlFile);
        }
    }

    private boolean hasDriver(String... classNames) {
        for (String className : classNames) {
            try {
                Class.forName(className);
                return true;
            } catch (ClassNotFoundException ignored) {
            }
        }
        return false;
    }

    private void createTables() {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + playersTable
                    + " (uuid VARCHAR(36) PRIMARY KEY, dark_mode INTEGER NOT NULL DEFAULT 0)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + linksTable
                    + " (xuid VARCHAR(64) PRIMARY KEY, bedrock_name VARCHAR(32), java_uuid VARCHAR(36) NOT NULL)");
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to create tables: " + e.getMessage());
        }
    }

    private <T> CompletableFuture<T> submit(Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        dbExecutor.execute(() -> {
            try {
                future.complete(task.call());
            } catch (Exception e) {
                plugin.getLogger().warning("Database operation failed: " + e.getMessage());
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public CompletableFuture<Boolean> isDarkMode(UUID uuid) {
        return submit(() -> {
            if (backend == Backend.YAML) {
                return yaml.getBoolean("players." + uuid + ".dark-mode", false);
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT dark_mode FROM " + playersTable + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getInt(1) == 1;
                }
            }
        });
    }

    public CompletableFuture<Void> setDarkMode(UUID uuid, boolean dark) {
        return submit(() -> {
            if (backend == Backend.YAML) {
                yaml.set("players." + uuid + ".dark-mode", dark);
                yaml.save(yamlFile);
                return null;
            }
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM " + playersTable + " WHERE uuid = ?")) {
                delete.setString(1, uuid.toString());
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO " + playersTable + " (uuid, dark_mode) VALUES (?, ?)")) {
                insert.setString(1, uuid.toString());
                insert.setInt(2, dark ? 1 : 0);
                insert.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> saveLink(String xuid, String bedrockName, UUID javaUuid) {
        return submit(() -> {
            if (backend == Backend.YAML) {
                yaml.set("links." + xuid + ".java", javaUuid.toString());
                yaml.set("links." + xuid + ".name", bedrockName);
                yaml.save(yamlFile);
                return null;
            }
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM " + linksTable + " WHERE xuid = ?")) {
                delete.setString(1, xuid);
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO " + linksTable + " (xuid, bedrock_name, java_uuid) VALUES (?, ?, ?)")) {
                insert.setString(1, xuid);
                insert.setString(2, bedrockName);
                insert.setString(3, javaUuid.toString());
                insert.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Optional<UUID>> getJavaForXuid(String xuid) {
        return submit(() -> {
            if (backend == Backend.YAML) {
                String stored = yaml.getString("links." + xuid + ".java");
                return stored == null ? Optional.empty() : Optional.of(UUID.fromString(stored));
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT java_uuid FROM " + linksTable + " WHERE xuid = ?")) {
                ps.setString(1, xuid);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(UUID.fromString(rs.getString(1))) : Optional.empty();
                }
            }
        });
    }

    public CompletableFuture<Optional<String>> getXuidForJava(UUID javaUuid) {
        return submit(() -> {
            if (backend == Backend.YAML) {
                ConfigurationSection links = yaml.getConfigurationSection("links");
                if (links != null) {
                    for (String xuid : links.getKeys(false)) {
                        if (javaUuid.toString().equals(links.getString(xuid + ".java"))) {
                            return Optional.of(xuid);
                        }
                    }
                }
                return Optional.empty();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT xuid FROM " + linksTable + " WHERE java_uuid = ?")) {
                ps.setString(1, javaUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
                }
            }
        });
    }

    public void close() {
        dbExecutor.shutdown();
        try {
            dbExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }
    }
}
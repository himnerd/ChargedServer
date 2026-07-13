package com.chargedserver.backup;

import com.chargedserver.ChargedServerPlugin;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class BackupManager {

    public static final List<String> PROVIDERS = List.of("dropbox", "googledrive", "icloud");

    private final ChargedServerPlugin plugin;
    private final HttpClient http;
    private final DateTimeFormatter timestamp;

    private ScheduledFuture<?> scheduledTask;

    public BackupManager(ChargedServerPlugin plugin) {
        this.plugin = plugin;
        int connectTimeout = plugin.getConfig().getInt("http.connect-timeout-seconds", 10);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeout))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.timestamp = DateTimeFormatter.ofPattern(
                plugin.getConfig().getString("backup.timestamp-format", "yyyy-MM-dd_HH-mm-ss"));
    }

    public void start() {
        reschedule();
    }

    public synchronized void reschedule() {
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
            scheduledTask = null;
        }
        long hours = plugin.getConfig().getLong("backup.interval-hours", 0);
        if (hours <= 0) return;
        long periodMs = hours * 3_600_000L;
        scheduledTask = plugin.getChargedScheduler().runAsyncRepeating(
                () -> backupBlocking("scheduled"), periodMs, periodMs);
    }

    public boolean link(String provider, String credential) {
        String normalized = provider.toLowerCase();
        if (!PROVIDERS.contains(normalized)) return false;
        plugin.getConfig().set("backup.provider", normalized);
        plugin.getConfig().set("backup.credential", credential);
        plugin.saveConfig();
        return true;
    }

    public void setRate(long hours) {
        plugin.getConfig().set("backup.interval-hours", hours);
        plugin.saveConfig();
        reschedule();
    }

    public String getProvider() {
        return plugin.getConfig().getString("backup.provider", "none");
    }

    public CompletableFuture<Boolean> backupNow(String reason) {
        return plugin.getChargedScheduler().supplyAsync(() -> backupBlocking(reason));
    }

    private boolean backupBlocking(String reason) {
        File zip = null;
        try {
            String dirName = plugin.getConfig().getString("backup.directory", "backups");
            File backupDir = new File(plugin.getDataFolder(), dirName);
            backupDir.mkdirs();
            zip = new File(backupDir, "backup-" + reason + "-"
                    + LocalDateTime.now().format(timestamp) + ".zip");
            zipPluginsFolder(zip, backupDir.toPath());

            String provider = getProvider();
            String credential = plugin.getConfig().getString("backup.credential", "");
            int uploadMinutes = plugin.getConfig().getInt("backup.upload-timeout-minutes", 5);
            switch (provider) {
                case "dropbox" -> uploadDropbox(zip, credential, uploadMinutes);
                case "googledrive" -> uploadGoogleDrive(zip, credential, uploadMinutes);
                case "icloud" -> copyToFolder(zip, credential);
                default -> { }
            }
            plugin.getLogger().info("Backup complete: " + zip.getName()
                    + ("none".equals(provider) ? " (local only)" : " (uploaded to " + provider + ")"));
            pruneOldBackups(backupDir);
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("Backup failed"
                    + (zip != null ? " (" + zip.getName() + ")" : "") + ": " + e.getMessage());
            return false;
        }
    }

    private void zipPluginsFolder(File zipFile, Path backupsDir) throws IOException {
        Path root = plugin.getDataFolder().getParentFile().toPath();
        List<String> excludes = plugin.getConfig().getStringList("backup.exclude");
        try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile)))) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .filter(path -> !path.startsWith(backupsDir))
                        .filter(path -> {
                            String relative = root.relativize(path).toString().replace('\\', '/');
                            for (String ex : excludes) {
                                if (relative.startsWith(ex)) return false;
                            }
                            return true;
                        })
                        .forEach(path -> {
                            try {
                                out.putNextEntry(new ZipEntry(
                                        root.relativize(path).toString().replace('\\', '/')));
                                Files.copy(path, out);
                                out.closeEntry();
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        });
            }
        }
    }

    private void pruneOldBackups(File backupDir) {
        int max = plugin.getConfig().getInt("backup.max-local-backups", 10);
        if (max <= 0) return;
        File[] files = backupDir.listFiles((dir, name) ->
                name.startsWith("backup-") && name.endsWith(".zip"));
        if (files == null || files.length <= max) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < files.length - max; i++) {
            if (files[i].delete()) {
                plugin.getLogger().info("Pruned old backup: " + files[i].getName());
            }
        }
    }

    private void uploadDropbox(File zip, String token, int timeoutMinutes) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("https://content.dropboxapi.com/2/files/upload"))
                .header("Authorization", "Bearer " + token)
                .header("Dropbox-API-Arg", "{\"path\":\"/ChargedServer/" + zip.getName()
                        + "\",\"mode\":\"add\",\"autorename\":true}")
                .header("Content-Type", "application/octet-stream")
                .timeout(Duration.ofMinutes(timeoutMinutes))
                .POST(HttpRequest.BodyPublishers.ofFile(zip.toPath()))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Dropbox upload failed: HTTP " + response.statusCode());
        }
    }

    private void uploadGoogleDrive(File zip, String token, int timeoutMinutes) throws Exception {
        String boundary = "charged" + System.currentTimeMillis();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"
                + "{\"name\":\"" + zip.getName() + "\"}\r\n"
                + "--" + boundary + "\r\nContent-Type: application/zip\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.write(Files.readAllBytes(zip.toPath()));
        body.write(("\r\n--" + boundary + "--").getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/related; boundary=" + boundary)
                .timeout(Duration.ofMinutes(timeoutMinutes))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Google Drive upload failed: HTTP " + response.statusCode());
        }
    }

    private void copyToFolder(File zip, String folderPath) throws IOException {
        File folder = new File(folderPath);
        if (!folder.isDirectory()) {
            throw new IOException("Linked folder does not exist: " + folderPath);
        }
        Files.copy(zip.toPath(), new File(folder, zip.getName()).toPath(),
                StandardCopyOption.REPLACE_EXISTING);
    }
}
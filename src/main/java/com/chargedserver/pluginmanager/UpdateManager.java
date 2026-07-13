package com.chargedserver.pluginmanager;

import com.chargedserver.ChargedServerPlugin;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class UpdateManager {

    private final ChargedServerPlugin plugin;
    private final HttpClient http;
    private final String userAgent;
    private final int apiTimeoutSeconds;
    private final int downloadTimeoutMinutes;

    private volatile List<String> cachedVersions = List.of();

    public UpdateManager(ChargedServerPlugin plugin) {
        this.plugin = plugin;
        int connectTimeout = plugin.getConfig().getInt("http.connect-timeout-seconds", 10);
        this.userAgent = plugin.getConfig().getString("http.user-agent",
                "ChargedServer/1.0 (https://chargedserver.com)");
        this.apiTimeoutSeconds = plugin.getConfig().getInt("http.api-timeout-seconds", 15);
        this.downloadTimeoutMinutes = plugin.getConfig().getInt("http.download-timeout-minutes", 2);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeout))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        fetchVersions();
    }

    public List<String> getCachedVersions() {
        return cachedVersions;
    }

    public CompletableFuture<List<String>> fetchVersions() {
        return plugin.getChargedScheduler().supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(
                                "https://fill.papermc.io/v3/projects/paper"))
                        .header("User-Agent", userAgent)
                        .timeout(Duration.ofSeconds(apiTimeoutSeconds))
                        .GET()
                        .build();
                HttpResponse<String> response = http.send(request,
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) return cachedVersions;
                JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                JsonObject versionsObj = root.getAsJsonObject("versions");
                List<String> list = new ArrayList<>();
                for (var entry : versionsObj.entrySet()) {
                    for (var ver : entry.getValue().getAsJsonArray()) {
                        list.add(ver.getAsString());
                    }
                }
                Collections.reverse(list);
                cachedVersions = list;
                return list;
            } catch (Exception e) {
                return cachedVersions;
            }
        });
    }

    public CompletableFuture<String> fetchChannel(String mcVersion) {
        return plugin.getChargedScheduler().supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(
                                "https://fill.papermc.io/v3/projects/paper/versions/"
                                        + mcVersion + "/builds"))
                        .header("User-Agent", userAgent)
                        .timeout(Duration.ofSeconds(apiTimeoutSeconds))
                        .GET()
                        .build();
                HttpResponse<String> response = http.send(request,
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) return null;
                var parsed = JsonParser.parseString(response.body());
                if (parsed.isJsonObject() && parsed.getAsJsonObject().has("ok")
                        && !parsed.getAsJsonObject().get("ok").getAsBoolean()) {
                    return null;
                }
                JsonArray builds = parsed.getAsJsonArray();
                if (builds == null || builds.isEmpty()) return null;
                JsonObject latest = builds.get(builds.size() - 1).getAsJsonObject();
                return latest.has("channel") ? latest.get("channel").getAsString() : "STABLE";
            } catch (Exception e) {
                return null;
            }
        });
    }

    public CompletableFuture<Boolean> queueUpdate(PluginInfo info) {
        if (info.getDownloadUrl() == null || info.isUpdateQueued()) {
            return CompletableFuture.completedFuture(false);
        }
        return plugin.getChargedScheduler().supplyAsync(() -> {
            try {
                File updateDir = new File(plugin.getDataFolder().getParentFile(), "update");
                updateDir.mkdirs();
                HttpRequest request = HttpRequest.newBuilder(URI.create(info.getDownloadUrl()))
                        .header("User-Agent", userAgent)
                        .timeout(Duration.ofMinutes(downloadTimeoutMinutes))
                        .GET()
                        .build();
                HttpResponse<InputStream> response = http.send(request,
                        HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) return false;
                Path target = new File(updateDir, info.getFileName()).toPath();
                try (InputStream in = response.body()) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                if (plugin.getConfig().getBoolean("plugin-manager.config-merge-on-update", true)) {
                    File dataFolder = new File(plugin.getDataFolder().getParentFile(), info.getName());
                    int added = ConfigMerger.mergeFromJar(target.toFile(), dataFolder,
                            plugin.getLogger());
                    if (added > 0) {
                        plugin.getLogger().info(info.getName() + ": " + added
                                + " new config option(s) merged from the update.");
                    }
                }
                info.setUpdateQueued(true);
                return true;
            } catch (Exception e) {
                plugin.getLogger().warning("Update download failed for "
                        + info.getName() + ": " + e.getMessage());
                return false;
            }
        });
    }

    public CompletableFuture<Integer> updateAll() {
        List<CompletableFuture<Boolean>> futures = new ArrayList<>();
        for (PluginInfo info : plugin.getPluginScanner().getPlugins()) {
            if (info.isUpdateAvailable() && !info.isUpdateQueued()) {
                futures.add(queueUpdate(info));
            }
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> (int) futures.stream().filter(CompletableFuture::join).count());
    }

    public CompletableFuture<String> downloadServerJar(String mcVersion) {
        return plugin.getChargedScheduler().supplyAsync(() -> {
            try {
                HttpRequest buildsRequest = HttpRequest.newBuilder(URI.create(
                                "https://fill.papermc.io/v3/projects/paper/versions/"
                                        + mcVersion + "/builds"))
                        .header("User-Agent", userAgent)
                        .timeout(Duration.ofSeconds(apiTimeoutSeconds))
                        .GET()
                        .build();
                HttpResponse<String> buildsResponse = http.send(buildsRequest,
                        HttpResponse.BodyHandlers.ofString());
                if (buildsResponse.statusCode() != 200) return null;
                var parsed = JsonParser.parseString(buildsResponse.body());
                if (parsed.isJsonObject() && parsed.getAsJsonObject().has("ok")
                        && !parsed.getAsJsonObject().get("ok").getAsBoolean()) {
                    return null;
                }
                JsonArray builds = parsed.getAsJsonArray();
                if (builds == null || builds.isEmpty()) return null;
                JsonObject latest = builds.get(builds.size() - 1).getAsJsonObject();
                JsonObject serverDownload = latest.getAsJsonObject("downloads")
                        .getAsJsonObject("server:default");
                String jarName = serverDownload.get("name").getAsString();
                String downloadUrl = serverDownload.get("url").getAsString();
                HttpRequest downloadRequest = HttpRequest.newBuilder(URI.create(downloadUrl))
                        .header("User-Agent", userAgent)
                        .timeout(Duration.ofMinutes(downloadTimeoutMinutes))
                        .GET()
                        .build();
                HttpResponse<InputStream> response = http.send(downloadRequest,
                        HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) return null;
                File serverRoot = plugin.getDataFolder().getParentFile().getParentFile();
                Path target = new File(serverRoot, jarName).toPath();
                try (InputStream in = response.body()) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return jarName;
            } catch (Exception e) {
                plugin.getLogger().warning("Server jar download failed for "
                        + mcVersion + ": " + e.getMessage());
                return null;
            }
        });
    }
}
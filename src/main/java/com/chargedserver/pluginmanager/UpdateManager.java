package com.chargedserver.pluginmanager;

import com.chargedserver.ChargedServerPlugin;

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
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Downloads updated jars into Bukkit's plugins/update folder. The server
 * replaces the old jar (matched by file name) automatically on the next
 * restart — the safe equivalent of hot-swapping without classloader leaks.
 */
public class UpdateManager {

    private final ChargedServerPlugin plugin;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public UpdateManager(ChargedServerPlugin plugin) {
        this.plugin = plugin;
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
                        .header("User-Agent", "ChargedServer/1.0")
                        .timeout(Duration.ofMinutes(2))
                        .GET()
                        .build();
                HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    return false;
                }
                // Keep the current file name so Bukkit's updater matches it.
                Path target = new File(updateDir, info.getFileName()).toPath();
                try (InputStream in = response.body()) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                info.setUpdateQueued(true);
                return true;
            } catch (Exception e) {
                plugin.getLogger().warning("Update download failed for " + info.getName() + ": " + e.getMessage());
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
}
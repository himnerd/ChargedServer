package com.chargedserver.pluginmanager;

import com.chargedserver.ChargedServerPlugin;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * Asynchronous plugins-folder scanner with Modrinth metadata matching.
 * All I/O (jar reading, HTTP) happens on the Charged worker pool. Version
 * lookups are filtered server-side by Modrinth to the exact running
 * Minecraft version, which enforces the compatibility guarantee.
 */
public class PluginScanner {

    private final ChargedServerPlugin plugin;
    private final Map<String, PluginInfo> plugins = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public PluginScanner(ChargedServerPlugin plugin) {
        this.plugin = plugin;
    }

    public List<PluginInfo> getPlugins() {
        return plugins.values().stream()
                .sorted(Comparator.comparing(PluginInfo::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public CompletableFuture<Void> scanNow() {
        return plugin.getChargedScheduler().runAsyncFuture(this::scanBlocking);
    }

    public CompletableFuture<Integer> checkUpdates() {
        return plugin.getChargedScheduler().supplyAsync(this::checkUpdatesBlocking);
    }

    /** Must be called off the main thread. */
    public void scanBlocking() {
        File pluginsDir = plugin.getDataFolder().getParentFile();
        File[] jars = pluginsDir.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (File jar : jars) {
            try (JarFile jarFile = new JarFile(jar)) {
                ZipEntry entry = jarFile.getEntry("plugin.yml");
                if (entry == null) {
                    entry = jarFile.getEntry("paper-plugin.yml");
                }
                if (entry == null) {
                    continue;
                }
                YamlConfiguration descriptor;
                try (InputStreamReader reader = new InputStreamReader(
                        jarFile.getInputStream(entry), StandardCharsets.UTF_8)) {
                    descriptor = YamlConfiguration.loadConfiguration(reader);
                }
                String name = descriptor.getString("name");
                if (name == null) {
                    continue;
                }
                seen.add(name);
                PluginInfo info = plugins.computeIfAbsent(name, key -> new PluginInfo());
                info.setName(name);
                info.setVersion(String.valueOf(descriptor.get("version", "?")));
                info.setDescription(descriptor.getString("description", ""));
                info.setFileName(jar.getName());
            } catch (Exception ignored) {
            }
        }
        plugins.keySet().retainAll(seen);
    }

    /** Must be called off the main thread. */
    public int checkUpdatesBlocking() {
        int updates = 0;
        for (PluginInfo info : plugins.values()) {
            checkPlugin(info);
            if (info.isUpdateAvailable()) {
                updates++;
            }
        }
        return updates;
    }

    private void checkPlugin(PluginInfo info) {
        try {
            if (info.getModrinthId() == null) {
                resolveModrinthProject(info);
            }
            if (info.getModrinthId() == null) {
                return;
            }
            String gameVersions = URLEncoder.encode(
                    "[\"" + Bukkit.getMinecraftVersion() + "\"]", StandardCharsets.UTF_8);
            String loaders = URLEncoder.encode(
                    "[\"paper\",\"spigot\",\"bukkit\"]", StandardCharsets.UTF_8);
            JsonElement response = fetch("https://api.modrinth.com/v2/project/"
                    + info.getModrinthId() + "/version?game_versions=" + gameVersions
                    + "&loaders=" + loaders);
            if (response == null || !response.isJsonArray()) {
                return;
            }
            JsonArray versions = response.getAsJsonArray();
            if (versions.isEmpty()) {
                return;
            }
            JsonObject latest = versions.get(0).getAsJsonObject();
            String versionNumber = latest.get("version_number").getAsString();
            if (versionNumber.equalsIgnoreCase(info.getVersion())) {
                info.setUpdateAvailable(false);
                info.setLatestVersion(null);
                return;
            }
            if (info.isUpdateQueued()) {
                return;
            }
            JsonArray files = latest.getAsJsonArray("files");
            if (files.isEmpty()) {
                return;
            }
            info.setLatestVersion(versionNumber);
            info.setDownloadUrl(files.get(0).getAsJsonObject().get("url").getAsString());
            info.setUpdateAvailable(true);
        } catch (Exception ignored) {
        }
    }

    private void resolveModrinthProject(PluginInfo info) throws Exception {
        String url = "https://api.modrinth.com/v2/search?limit=5&query="
                + URLEncoder.encode(info.getName(), StandardCharsets.UTF_8)
                + "&facets=" + URLEncoder.encode("[[\"project_type:plugin\"]]", StandardCharsets.UTF_8);
        JsonElement response = fetch(url);
        if (response == null || !response.isJsonObject()) {
            return;
        }
        for (JsonElement element : response.getAsJsonObject().getAsJsonArray("hits")) {
            JsonObject hit = element.getAsJsonObject();
            String title = hit.get("title").getAsString();
            String slug = hit.get("slug").getAsString();
            // Exact name match only — avoids downloading the wrong project.
            if (title.equalsIgnoreCase(info.getName()) || slug.equalsIgnoreCase(info.getName())) {
                info.setModrinthId(hit.get("project_id").getAsString());
                return;
            }
        }
    }

    private JsonElement fetch(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "ChargedServer/1.0")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }
        return JsonParser.parseString(response.body());
    }
}
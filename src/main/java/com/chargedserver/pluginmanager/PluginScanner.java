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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

/**
 * Scans installed plugins and checks for updates across multiple platforms:
 * Modrinth, Hangar, SpigotMC (via Spiget), GitHub Releases, and generic
 * website scraping as a last resort. All I/O runs on the Charged worker pool.
 */
public class PluginScanner {

    private static final Pattern SPIGOT_ID_PATTERN = Pattern.compile("[./](\\d+)/?$");
    private static final Pattern GITHUB_REPO_PATTERN = Pattern.compile("github\\.com/([^/]+/[^/]+)");
    private static final Pattern JAR_HREF_PATTERN = Pattern.compile(
            "href=[\"']([^\"']*\\.jar)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern VERSION_NUMBER_PATTERN = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)*)");

    private static final Map<String, String> GEYSERMC_PROJECTS = Map.of(
            "geyser-spigot", "geyser",
            "geyser", "geyser",
            "floodgate", "floodgate",
            "floodgate-bukkit", "floodgate",
            "floodgate-spigot", "floodgate"
    );
    private static final String GEYSERMC_API = "https://download.geysermc.org/v2/projects/";

    private final ChargedServerPlugin plugin;
    private final Map<String, PluginInfo> plugins = new ConcurrentHashMap<>();
    private final HttpClient http;
    private final String userAgent;
    private final int apiTimeoutSeconds;

    public PluginScanner(ChargedServerPlugin plugin) {
        this.plugin = plugin;
        int connectTimeout = plugin.getConfig().getInt("http.connect-timeout-seconds", 10);
        this.apiTimeoutSeconds = plugin.getConfig().getInt("http.api-timeout-seconds", 15);
        this.userAgent = plugin.getConfig().getString("http.user-agent", "ChargedServer/1.0 (https://chargedserver.com)");
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeout))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
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
        return checkUpdatesFor(Bukkit.getMinecraftVersion());
    }

    public CompletableFuture<Integer> checkUpdatesFor(String mcVersion) {
        return plugin.getChargedScheduler().supplyAsync(() -> checkUpdatesBlocking(mcVersion));
    }

    public record CompatibilityReport(List<String> incompatible, List<String> unknown) {
    }

    public CompletableFuture<CompatibilityReport> checkCompatibilityFor(String mcVersion) {
        return plugin.getChargedScheduler().supplyAsync(() -> {
            List<String> incompatible = new ArrayList<>();
            List<String> unknown = new ArrayList<>();
            for (PluginInfo info : plugins.values()) {
                try {
                    if (!resolveProject(info)) {
                        unknown.add(info.getName());
                        continue;
                    }
                    if (!hasVersionFor(info, mcVersion)) {
                        incompatible.add(info.getName());
                    }
                } catch (Exception e) {
                    unknown.add(info.getName());
                }
            }
            incompatible.sort(String.CASE_INSENSITIVE_ORDER);
            unknown.sort(String.CASE_INSENSITIVE_ORDER);
            return new CompatibilityReport(incompatible, unknown);
        });
    }

    // ==================== Jar Scanning ====================

    public void scanBlocking() {
        File pluginsDir = plugin.getDataFolder().getParentFile();
        File[] jars = pluginsDir.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars == null) return;
        Set<String> seen = new HashSet<>();
        for (File jar : jars) {
            try (JarFile jarFile = new JarFile(jar)) {
                ZipEntry entry = jarFile.getEntry("plugin.yml");
                if (entry == null) entry = jarFile.getEntry("paper-plugin.yml");
                if (entry == null) continue;
                YamlConfiguration descriptor;
                try (InputStreamReader reader = new InputStreamReader(
                        jarFile.getInputStream(entry), StandardCharsets.UTF_8)) {
                    descriptor = YamlConfiguration.loadConfiguration(reader);
                }
                String name = descriptor.getString("name");
                if (name == null) continue;
                seen.add(name);
                PluginInfo info = plugins.computeIfAbsent(name, key -> new PluginInfo());
                info.setName(name);
                info.setVersion(String.valueOf(descriptor.get("version", "?")));
                info.setDescription(descriptor.getString("description", ""));
                info.setFileName(jar.getName());
                String website = descriptor.getString("website", "");
                if (website.isEmpty()) website = descriptor.getString("url", "");
                info.setWebsite(website);
            } catch (Exception ignored) {
            }
        }
        plugins.keySet().retainAll(seen);
    }

    // ==================== Update Checking ====================

    public int checkUpdatesBlocking() {
        return checkUpdatesBlocking(Bukkit.getMinecraftVersion());
    }

    public int checkUpdatesBlocking(String mcVersion) {
        boolean retargeting = !mcVersion.equals(Bukkit.getMinecraftVersion());
        int updates = 0;
        for (PluginInfo info : plugins.values()) {
            if (retargeting) info.setUpdateQueued(false);
            checkPlugin(info, mcVersion);
            if (info.isUpdateAvailable()) updates++;
        }
        return updates;
    }

    private void checkPlugin(PluginInfo info, String mcVersion) {
        resolveFromWebsite(info);
        if (isSourceEnabled("geysermc") && tryGeyserMC(info)) return;
        if (isSourceEnabled("modrinth") && tryModrinth(info, mcVersion)) return;
        if (isSourceEnabled("hangar") && tryHangar(info, mcVersion)) return;
        if (isSourceEnabled("spigotmc") && trySpiget(info, mcVersion)) return;
        if (isSourceEnabled("github") && tryGitHub(info)) return;
        if (isSourceEnabled("website-scrape")) tryGenericWebsite(info);
    }

    private boolean isSourceEnabled(String source) {
        return plugin.getConfig().getBoolean("plugin-manager.sources." + source, true);
    }

    // ==================== Website URL Pre-Resolution ====================

    private void resolveFromWebsite(PluginInfo info) {
        String url = info.getWebsite();
        if (url == null || url.isEmpty()) return;
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) return;
            host = host.toLowerCase();
            String path = uri.getPath();
            if (path == null) return;

            if (host.contains("spigotmc.org") && info.getSpigotId() == null) {
                Matcher m = SPIGOT_ID_PATTERN.matcher(path);
                if (m.find()) info.setSpigotId(Integer.parseInt(m.group(1)));
            } else if (host.contains("github.com") && info.getGithubRepo() == null) {
                Matcher m = GITHUB_REPO_PATTERN.matcher(url);
                if (m.find()) {
                    String repo = m.group(1);
                    if (repo.endsWith("/")) repo = repo.substring(0, repo.length() - 1);
                    info.setGithubRepo(repo);
                }
            } else if (host.contains("modrinth.com") && info.getModrinthId() == null) {
                String[] parts = path.split("/");
                if (parts.length >= 3) {
                    try {
                        JsonElement resp = fetch("https://api.modrinth.com/v2/project/" + parts[2]);
                        if (resp != null && resp.isJsonObject()) {
                            info.setModrinthId(resp.getAsJsonObject().get("id").getAsString());
                        }
                    } catch (Exception ignored) {
                    }
                }
            } else if (host.contains("hangar.papermc.io") && info.getHangarSlug() == null) {
                String[] parts = path.split("/");
                if (parts.length >= 3) info.setHangarSlug(parts[2]);
            }
        } catch (Exception ignored) {
        }
    }

    // ==================== GeyserMC Download API ====================

    private String geyserMCProject(PluginInfo info) {
        String key = info.getName().toLowerCase().replace(" ", "-");
        return GEYSERMC_PROJECTS.get(key);
    }

    private boolean tryGeyserMC(PluginInfo info) {
        String project = geyserMCProject(info);
        if (project == null) return false;
        try {
            JsonElement response = fetch(GEYSERMC_API + project
                    + "/versions/latest/builds/latest");
            if (response == null || !response.isJsonObject()) return false;
            JsonObject json = response.getAsJsonObject();
            if (json.has("ok") && !json.get("ok").getAsBoolean()) return false;

            String latestVersion = json.get("version").getAsString();
            int buildNumber = json.get("build").getAsInt();
            String fullVersion = latestVersion + "-b" + buildNumber;

            String current = info.getVersion();
            boolean upToDate = current.equals(latestVersion)
                    || current.equals(fullVersion)
                    || current.startsWith(latestVersion + "-")
                    && extractBuild(current) >= buildNumber;

            if (upToDate) {
                info.setUpdateAvailable(false);
                info.setLatestVersion(null);
                info.setSource("GeyserMC");
                return true;
            }
            if (info.isUpdateQueued()) return true;

            String platform = detectGeyserPlatform(json);
            if (platform == null) return true;

            String downloadUrl = GEYSERMC_API + project
                    + "/versions/latest/builds/latest/downloads/" + platform;
            info.setLatestVersion(fullVersion);
            info.setDownloadUrl(downloadUrl);
            info.setUpdateAvailable(true);
            info.setSource("GeyserMC");
            log(info, "GeyserMC");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String detectGeyserPlatform(JsonObject buildJson) {
        JsonObject downloads = buildJson.getAsJsonObject("downloads");
        if (downloads == null) return null;
        for (String key : new String[]{"spigot", "paper", "bukkit"}) {
            if (downloads.has(key)) return key;
        }
        return null;
    }

    private int extractBuild(String version) {
        int idx = version.lastIndexOf("-b");
        if (idx < 0) return -1;
        try {
            return Integer.parseInt(version.substring(idx + 2));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ==================== Modrinth ====================

    private boolean tryModrinth(PluginInfo info, String mcVersion) {
        try {
            if (info.getModrinthId() == null) resolveModrinthProject(info);
            if (info.getModrinthId() == null) return false;

            String gameVersions = URLEncoder.encode("[\"" + mcVersion + "\"]", StandardCharsets.UTF_8);
            String loaders = URLEncoder.encode("[\"paper\",\"spigot\",\"bukkit\"]", StandardCharsets.UTF_8);
            JsonElement response = fetch("https://api.modrinth.com/v2/project/"
                    + info.getModrinthId() + "/version?game_versions=" + gameVersions
                    + "&loaders=" + loaders);
            if (response == null || !response.isJsonArray()) return true;
            JsonArray versions = response.getAsJsonArray();
            if (versions.isEmpty()) return true;

            JsonObject latest = versions.get(0).getAsJsonObject();
            String versionNumber = latest.get("version_number").getAsString();
            if (versionNumber.equalsIgnoreCase(info.getVersion())) {
                info.setUpdateAvailable(false);
                info.setLatestVersion(null);
                info.setSource("Modrinth");
                return true;
            }
            if (info.isUpdateQueued()) return true;
            JsonArray files = latest.getAsJsonArray("files");
            if (files.isEmpty()) return true;

            info.setLatestVersion(versionNumber);
            info.setDownloadUrl(files.get(0).getAsJsonObject().get("url").getAsString());
            info.setUpdateAvailable(true);
            info.setSource("Modrinth");
            log(info, "Modrinth");
            return true;
        } catch (Exception e) {
            return info.getModrinthId() != null;
        }
    }

    private void resolveModrinthProject(PluginInfo info) throws Exception {
        String url = "https://api.modrinth.com/v2/search?limit=5&query="
                + URLEncoder.encode(info.getName(), StandardCharsets.UTF_8)
                + "&facets=" + URLEncoder.encode("[[\"project_type:plugin\"]]", StandardCharsets.UTF_8);
        JsonElement response = fetch(url);
        if (response == null || !response.isJsonObject()) return;
        for (JsonElement element : response.getAsJsonObject().getAsJsonArray("hits")) {
            JsonObject hit = element.getAsJsonObject();
            String title = hit.get("title").getAsString();
            String slug = hit.get("slug").getAsString();
            if (title.equalsIgnoreCase(info.getName()) || slug.equalsIgnoreCase(info.getName())) {
                info.setModrinthId(hit.get("project_id").getAsString());
                return;
            }
        }
    }

    private boolean hasModrinthVersionFor(PluginInfo info, String mcVersion) throws Exception {
        String gameVersions = URLEncoder.encode("[\"" + mcVersion + "\"]", StandardCharsets.UTF_8);
        String loaders = URLEncoder.encode("[\"paper\",\"spigot\",\"bukkit\"]", StandardCharsets.UTF_8);
        JsonElement response = fetch("https://api.modrinth.com/v2/project/"
                + info.getModrinthId() + "/version?game_versions=" + gameVersions
                + "&loaders=" + loaders);
        return response != null && response.isJsonArray() && !response.getAsJsonArray().isEmpty();
    }

    // ==================== Hangar ====================

    private boolean tryHangar(PluginInfo info, String mcVersion) {
        try {
            if (info.getHangarSlug() == null) resolveHangarProject(info);
            if (info.getHangarSlug() == null) return false;

            String versionsUrl = "https://hangar.papermc.io/api/v1/projects/"
                    + info.getHangarSlug() + "/versions?limit=1&offset=0&platform=PAPER";
            JsonElement response = fetch(versionsUrl);
            if (response == null || !response.isJsonObject()) return true;
            JsonArray result = response.getAsJsonObject().getAsJsonArray("result");
            if (result == null || result.isEmpty()) return true;

            JsonObject latest = result.get(0).getAsJsonObject();
            String versionName = latest.get("name").getAsString();
            if (versionName.equalsIgnoreCase(info.getVersion())) {
                info.setUpdateAvailable(false);
                info.setLatestVersion(null);
                info.setSource("Hangar");
                return true;
            }
            if (info.isUpdateQueued()) return true;

            JsonObject downloads = latest.getAsJsonObject("downloads");
            if (downloads != null && downloads.has("PAPER")) {
                JsonObject paperDl = downloads.getAsJsonObject("PAPER");
                if (paperDl.has("downloadUrl") && !paperDl.get("downloadUrl").isJsonNull()) {
                    info.setDownloadUrl("https://hangar.papermc.io"
                            + paperDl.get("downloadUrl").getAsString());
                } else if (paperDl.has("externalUrl") && !paperDl.get("externalUrl").isJsonNull()) {
                    info.setDownloadUrl(paperDl.get("externalUrl").getAsString());
                }
            }

            info.setLatestVersion(versionName);
            info.setUpdateAvailable(info.getDownloadUrl() != null);
            info.setSource("Hangar");
            if (info.isUpdateAvailable()) log(info, "Hangar");
            return true;
        } catch (Exception e) {
            return info.getHangarSlug() != null;
        }
    }

    private void resolveHangarProject(PluginInfo info) throws Exception {
        String url = "https://hangar.papermc.io/api/v1/projects?q="
                + URLEncoder.encode(info.getName(), StandardCharsets.UTF_8)
                + "&limit=5&platform=PAPER";
        JsonElement response = fetch(url);
        if (response == null || !response.isJsonObject()) return;
        JsonArray result = response.getAsJsonObject().getAsJsonArray("result");
        if (result == null) return;
        for (JsonElement elem : result) {
            JsonObject project = elem.getAsJsonObject();
            String name = project.get("name").getAsString();
            JsonObject ns = project.getAsJsonObject("namespace");
            String slug = ns.get("slug").getAsString();
            if (name.equalsIgnoreCase(info.getName()) || slug.equalsIgnoreCase(info.getName())) {
                info.setHangarSlug(slug);
                return;
            }
        }
    }

    private boolean hasHangarVersionFor(PluginInfo info, String mcVersion) throws Exception {
        String url = "https://hangar.papermc.io/api/v1/projects/" + info.getHangarSlug()
                + "/versions?limit=1&platform=PAPER&platformVersion="
                + URLEncoder.encode(mcVersion, StandardCharsets.UTF_8);
        JsonElement response = fetch(url);
        if (response == null || !response.isJsonObject()) return false;
        JsonArray result = response.getAsJsonObject().getAsJsonArray("result");
        return result != null && !result.isEmpty();
    }

    // ==================== SpigotMC (Spiget API) ====================

    private boolean trySpiget(PluginInfo info, String mcVersion) {
        try {
            if (info.getSpigotId() == null) resolveSpigetResource(info);
            if (info.getSpigotId() == null) return false;

            JsonElement verResp = fetch("https://api.spiget.org/v2/resources/"
                    + info.getSpigotId() + "/versions/latest");
            if (verResp == null || !verResp.isJsonObject()) return true;
            String latestVer = verResp.getAsJsonObject().get("name").getAsString();

            if (latestVer.equalsIgnoreCase(info.getVersion())) {
                info.setUpdateAvailable(false);
                info.setLatestVersion(null);
                info.setSource("SpigotMC");
                return true;
            }
            if (info.isUpdateQueued()) return true;

            JsonElement resResp = fetch("https://api.spiget.org/v2/resources/" + info.getSpigotId());
            boolean downloadable = false;
            if (resResp != null && resResp.isJsonObject()) {
                JsonObject resource = resResp.getAsJsonObject();
                boolean premium = resource.has("premium") && resource.get("premium").getAsBoolean();
                if (!premium) {
                    info.setDownloadUrl("https://api.spiget.org/v2/resources/"
                            + info.getSpigotId() + "/download");
                    downloadable = true;
                }
            }

            info.setLatestVersion(latestVer);
            info.setUpdateAvailable(downloadable);
            info.setSource("SpigotMC");
            if (downloadable) log(info, "SpigotMC");
            return true;
        } catch (Exception e) {
            return info.getSpigotId() != null;
        }
    }

    private void resolveSpigetResource(PluginInfo info) throws Exception {
        String url = "https://api.spiget.org/v2/search/resources/"
                + URLEncoder.encode(info.getName(), StandardCharsets.UTF_8)
                + "?field=name&size=5";
        JsonElement response = fetch(url);
        if (response == null || !response.isJsonArray()) return;
        for (JsonElement elem : response.getAsJsonArray()) {
            JsonObject resource = elem.getAsJsonObject();
            String name = resource.get("name").getAsString();
            String cleanName = name.replaceAll("[§&][0-9a-fk-orA-FK-OR]", "").trim();
            if (cleanName.equalsIgnoreCase(info.getName())) {
                info.setSpigotId(resource.get("id").getAsInt());
                return;
            }
        }
    }

    private boolean hasSpigetVersionFor(PluginInfo info, String mcVersion) throws Exception {
        JsonElement response = fetch("https://api.spiget.org/v2/resources/" + info.getSpigotId());
        if (response == null || !response.isJsonObject()) return false;
        JsonArray tested = response.getAsJsonObject().getAsJsonArray("testedVersions");
        if (tested == null) return false;
        String shortVersion = mcVersion;
        int lastDot = mcVersion.lastIndexOf('.');
        if (lastDot > mcVersion.indexOf('.')) {
            shortVersion = mcVersion.substring(0, lastDot);
        }
        for (JsonElement v : tested) {
            String tv = v.getAsString();
            if (mcVersion.startsWith(tv) || tv.equals(shortVersion) || tv.equals(mcVersion)) {
                return true;
            }
        }
        return false;
    }

    // ==================== GitHub Releases ====================

    private boolean tryGitHub(PluginInfo info) {
        try {
            if (info.getGithubRepo() == null) return false;

            JsonElement response = fetch("https://api.github.com/repos/"
                    + info.getGithubRepo() + "/releases/latest");
            if (response == null || !response.isJsonObject()) return false;
            JsonObject release = response.getAsJsonObject();
            if (release.has("message")) return false;

            String tagName = release.get("tag_name").getAsString();
            String cleanTag = tagName.replaceFirst("^[vV]", "");

            if (cleanTag.equalsIgnoreCase(info.getVersion())
                    || tagName.equalsIgnoreCase(info.getVersion())) {
                info.setUpdateAvailable(false);
                info.setLatestVersion(null);
                info.setSource("GitHub");
                return true;
            }
            if (info.isUpdateQueued()) return true;

            JsonArray assets = release.getAsJsonArray("assets");
            if (assets != null) {
                for (JsonElement asset : assets) {
                    JsonObject a = asset.getAsJsonObject();
                    if (a.get("name").getAsString().endsWith(".jar")) {
                        info.setDownloadUrl(a.get("browser_download_url").getAsString());
                        break;
                    }
                }
            }

            info.setLatestVersion(cleanTag);
            info.setUpdateAvailable(info.getDownloadUrl() != null);
            info.setSource("GitHub");
            if (info.isUpdateAvailable()) log(info, "GitHub");
            return true;
        } catch (Exception e) {
            return info.getGithubRepo() != null;
        }
    }

    // ==================== Generic Website Scraping ====================

    private void tryGenericWebsite(PluginInfo info) {
        String url = info.getWebsite();
        if (url == null || url.isEmpty()) return;
        if (info.getModrinthId() != null || info.getHangarSlug() != null
                || info.getSpigotId() != null || info.getGithubRepo() != null) return;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", userAgent)
                    .timeout(Duration.ofSeconds(apiTimeoutSeconds))
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return;
            String body = resp.body();

            Matcher matcher = JAR_HREF_PATTERN.matcher(body);
            String bestUrl = null;
            String bestVersion = null;
            while (matcher.find()) {
                String jarUrl = matcher.group(1);
                Matcher vm = VERSION_NUMBER_PATTERN.matcher(jarUrl);
                if (vm.find()) {
                    String ver = vm.group(1);
                    if (!ver.equalsIgnoreCase(info.getVersion())) {
                        bestUrl = jarUrl;
                        bestVersion = ver;
                    }
                }
            }

            if (bestUrl != null) {
                if (!bestUrl.startsWith("http")) {
                    bestUrl = URI.create(url).resolve(bestUrl).toString();
                }
                info.setLatestVersion(bestVersion);
                info.setDownloadUrl(bestUrl);
                info.setUpdateAvailable(true);
                info.setSource("Website");
                log(info, info.getWebsite());
            }
        } catch (Exception ignored) {
        }
    }

    // ==================== Multi-Source Resolution & Compatibility ====================

    private boolean resolveProject(PluginInfo info) {
        if (geyserMCProject(info) != null) return true;
        if (info.getModrinthId() != null || info.getHangarSlug() != null
                || info.getSpigotId() != null || info.getGithubRepo() != null) {
            return true;
        }
        resolveFromWebsite(info);
        if (info.getModrinthId() != null || info.getHangarSlug() != null
                || info.getSpigotId() != null || info.getGithubRepo() != null) {
            return true;
        }
        try { resolveModrinthProject(info); } catch (Exception ignored) {}
        if (info.getModrinthId() != null) return true;
        try { resolveHangarProject(info); } catch (Exception ignored) {}
        if (info.getHangarSlug() != null) return true;
        try { resolveSpigetResource(info); } catch (Exception ignored) {}
        return info.getSpigotId() != null || info.getGithubRepo() != null;
    }

    private boolean hasVersionFor(PluginInfo info, String mcVersion) throws Exception {
        if (geyserMCProject(info) != null) return true;
        if (info.getModrinthId() != null && hasModrinthVersionFor(info, mcVersion)) return true;
        if (info.getHangarSlug() != null && hasHangarVersionFor(info, mcVersion)) return true;
        if (info.getSpigotId() != null && hasSpigetVersionFor(info, mcVersion)) return true;
        return false;
    }

    // ==================== Utility ====================

    private void log(PluginInfo info, String source) {
        plugin.getLogger().info(info.getName() + ": update available via " + source
                + " (" + info.getVersion() + " → " + info.getLatestVersion() + ")");
    }

    private JsonElement fetch(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", userAgent)
                .timeout(Duration.ofSeconds(apiTimeoutSeconds))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return null;
        return JsonParser.parseString(response.body());
    }
}
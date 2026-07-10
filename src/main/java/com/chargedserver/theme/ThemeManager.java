package com.chargedserver.theme;

import com.chargedserver.ChargedServerPlugin;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player dark mode preferences. Reads hit an in-memory cache populated
 * asynchronously at join; writes go straight to storage off-thread.
 */
public class ThemeManager {

    private final ChargedServerPlugin plugin;
    private final Map<UUID, Boolean> darkMode = new ConcurrentHashMap<>();

    public ThemeManager(ChargedServerPlugin plugin) {
        this.plugin = plugin;
    }

    public void load(UUID uuid) {
        plugin.getDatabaseManager().isDarkMode(uuid).thenAccept(dark -> darkMode.put(uuid, dark));
    }

    public void unload(UUID uuid) {
        darkMode.remove(uuid);
    }

    public boolean isDark(UUID uuid) {
        return darkMode.getOrDefault(uuid, false);
    }

    public Theme theme(UUID uuid) {
        return isDark(uuid) ? Theme.DARK : Theme.LIGHT;
    }

    public CompletableFuture<Boolean> toggle(Player player) {
        boolean newValue = !isDark(player.getUniqueId());
        darkMode.put(player.getUniqueId(), newValue);
        return plugin.getDatabaseManager().setDarkMode(player.getUniqueId(), newValue)
                .thenApply(v -> newValue);
    }
}
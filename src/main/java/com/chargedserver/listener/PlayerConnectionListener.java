package com.chargedserver.listener;

import com.chargedserver.ChargedServerPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Manages per-player lifecycle: injects the Netty handler on join, loads
 * dark mode preferences, and cleans up all cached data on quit.
 */
public class PlayerConnectionListener implements Listener {

    private final ChargedServerPlugin plugin;

    public PlayerConnectionListener(ChargedServerPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        plugin.getPacketInjector().inject(player);
        plugin.getThemeManager().load(player.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        var player = event.getPlayer();
        plugin.getPacketInjector().uninject(player.getUniqueId());
        plugin.getThemeManager().unload(player.getUniqueId());
    }
}
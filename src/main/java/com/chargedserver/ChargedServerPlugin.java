package com.chargedserver;

import com.chargedserver.account.ChargedLinkManager;
import com.chargedserver.account.LinkManager;
import com.chargedserver.api.ChargedAPI;
import com.chargedserver.command.ChargedCommand;
import com.chargedserver.database.DatabaseManager;
import com.chargedserver.event.ChargedEventBus;
import com.chargedserver.gui.GUIListener;
import com.chargedserver.gui.GUIManager;
import com.chargedserver.listener.PlayerConnectionListener;
import com.chargedserver.metrics.PerformanceMonitor;
import com.chargedserver.optimization.EntityCuller;
import com.chargedserver.pluginmanager.PluginScanner;
import com.chargedserver.pluginmanager.UpdateManager;
import com.chargedserver.protocol.PacketInjector;
import com.chargedserver.protocol.PacketThrottler;
import com.chargedserver.scheduler.ChargedScheduler;
import com.chargedserver.theme.ThemeManager;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.CompletableFuture;

public final class ChargedServerPlugin extends JavaPlugin {

    @Getter private static ChargedServerPlugin instance;

    @Getter private ChargedScheduler chargedScheduler;
    @Getter private ChargedEventBus eventBus;
    @Getter private DatabaseManager databaseManager;
    @Getter private ThemeManager themeManager;
    @Getter private GUIManager guiManager;
    @Getter private PluginScanner pluginScanner;
    @Getter private UpdateManager updateManager;
    @Getter private PacketInjector packetInjector;
    @Getter private PacketThrottler packetThrottler;
    @Getter private EntityCuller entityCuller;
    @Getter private PerformanceMonitor performanceMonitor;
    @Getter private LinkManager linkManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        // Core infrastructure first: everything else schedules through these.
        chargedScheduler = new ChargedScheduler(this);
        eventBus = new ChargedEventBus();

        databaseManager = new DatabaseManager(this);
        databaseManager.init().thenRun(() ->
                getLogger().info("Storage backend in use: " + databaseManager.getBackend()));

        themeManager = new ThemeManager(this);
        guiManager = new GUIManager();

        pluginScanner = new PluginScanner(this);
        updateManager = new UpdateManager(this);

        packetInjector = new PacketInjector(this);
        packetThrottler = new PacketThrottler(this);
        packetInjector.registerListener(packetThrottler);

        entityCuller = new EntityCuller(this);
        entityCuller.start();

        performanceMonitor = new PerformanceMonitor(entityCuller, packetThrottler);
        linkManager = new ChargedLinkManager(this);

        ChargedAPI.init(this);

        Bukkit.getPluginManager().registerEvents(new GUIListener(guiManager), this);
        Bukkit.getPluginManager().registerEvents(new PlayerConnectionListener(this), this);

        ChargedCommand command = new ChargedCommand(this);
        getCommand("charged").setExecutor(command);
        getCommand("charged").setTabCompleter(command);

        // Handle /reload or late enables: hook players already online.
        for (Player online : Bukkit.getOnlinePlayers()) {
            themeManager.load(online.getUniqueId());
            packetInjector.inject(online);
        }

        boolean modrinth = getConfig().getBoolean("plugin-manager.modrinth-check", true);
        pluginScanner.scanNow()
                .thenCompose(v -> modrinth ? pluginScanner.checkUpdates() : CompletableFuture.completedFuture(0))
                .thenAccept(updates -> getLogger().info("Scanned " + pluginScanner.getPlugins().size()
                        + " plugins, " + updates + " update(s) available."));

        long intervalMs = getConfig().getLong("plugin-manager.scan-interval-seconds", 300) * 1000L;
        chargedScheduler.runAsyncRepeating(() -> {
            pluginScanner.scanBlocking();
            if (modrinth) {
                pluginScanner.checkUpdatesBlocking();
            }
        }, intervalMs, intervalMs);

        getLogger().info("ChargedServer enabled.");
    }

    @Override
    public void onDisable() {
        if (entityCuller != null) entityCuller.shutdown();
        if (packetInjector != null) packetInjector.shutdown();
        if (chargedScheduler != null) chargedScheduler.shutdown();
        if (databaseManager != null) databaseManager.close();
        ChargedAPI.shutdown();
        instance = null;
    }
}
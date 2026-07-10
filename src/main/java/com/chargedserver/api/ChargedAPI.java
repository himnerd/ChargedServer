package com.chargedserver.api;

import com.chargedserver.ChargedServerPlugin;
import com.chargedserver.account.LinkManager;
import com.chargedserver.event.ChargedEventBus;
import com.chargedserver.gui.GUIManager;
import com.chargedserver.metrics.PerformanceSnapshot;
import com.chargedserver.protocol.PacketInjector;
import com.chargedserver.scheduler.ChargedScheduler;
import com.chargedserver.theme.ThemeManager;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Singleton entry point for external plugins. All returned futures complete
 * off the main thread; use {@link ChargedScheduler#runSync(Runnable)} to hop
 * back onto the server thread when touching world state.
 */
public final class ChargedAPI {

    private static volatile ChargedAPI instance;

    private final ChargedServerPlugin plugin;
    private final List<ChargedModule> modules = new CopyOnWriteArrayList<>();

    private ChargedAPI(ChargedServerPlugin plugin) {
        this.plugin = plugin;
    }

    public static void init(ChargedServerPlugin plugin) {
        instance = new ChargedAPI(plugin);
    }

    public static void shutdown() {
        instance = null;
    }

    public static ChargedAPI get() {
        ChargedAPI api = instance;
        if (api == null) {
            throw new IllegalStateException("ChargedServer is not enabled");
        }
        return api;
    }

    public ChargedScheduler scheduler() {
        return plugin.getChargedScheduler();
    }

    public ChargedEventBus eventBus() {
        return plugin.getEventBus();
    }

    public GUIManager guiManager() {
        return plugin.getGuiManager();
    }

    public ThemeManager themeManager() {
        return plugin.getThemeManager();
    }

    public LinkManager linkManager() {
        return plugin.getLinkManager();
    }

    public PacketInjector protocol() {
        return plugin.getPacketInjector();
    }

    /** Registers a module and invokes its load hook asynchronously. Thread-safe. */
    public void registerModule(ChargedModule module) {
        modules.add(module);
        scheduler().runAsync(() -> module.onLoad(this));
    }

    public List<ChargedModule> modules() {
        return List.copyOf(modules);
    }

    /** Captures a performance snapshot without blocking the caller. */
    public CompletableFuture<PerformanceSnapshot> metrics() {
        return scheduler().supplyAsync(() -> plugin.getPerformanceMonitor().snapshot());
    }

    /** Async player data access: dark mode preference straight from storage. */
    public CompletableFuture<Boolean> isDarkMode(UUID uuid) {
        return plugin.getDatabaseManager().isDarkMode(uuid);
    }
}
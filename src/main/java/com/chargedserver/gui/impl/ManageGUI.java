package com.chargedserver.gui.impl;

import com.chargedserver.ChargedServerPlugin;
import com.chargedserver.gui.InventoryButton;
import com.chargedserver.gui.InventoryGUI;
import com.chargedserver.metrics.PerformanceSnapshot;
import com.chargedserver.pluginmanager.PluginInfo;
import com.chargedserver.theme.Theme;
import com.chargedserver.util.ColorUtil;
import com.chargedserver.util.Items;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Paginated plugin management GUI. The theme (light/dark) is resolved once
 * per open; the dark mode button persists the preference asynchronously and
 * reopens the GUI with the swapped palette. MiniMessage strings are parsed
 * once per decorate pass — never inside the click path.
 */
public class ManageGUI extends InventoryGUI {

    private static final int[] CONTENT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private final ChargedServerPlugin plugin;
    private final Player viewer;
    private final Theme theme;
    private final int page;
    private final int totalPages;

    public ManageGUI(ChargedServerPlugin plugin, Player viewer, int page) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.theme = plugin.getThemeManager().theme(viewer.getUniqueId());
        int count = plugin.getPluginScanner().getPlugins().size();
        this.totalPages = Math.max(1, (int) Math.ceil(count / (double) CONTENT_SLOTS.length));
        this.page = Math.min(Math.max(page, 0), totalPages - 1);
    }

    @Override
    protected Inventory createInventory() {
        return Bukkit.createInventory(null, 54, ColorUtil.colorize(
                theme.primary() + "<bold>Charged</bold> " + theme.secondary()
                        + "Plugin Manager (" + (page + 1) + "/" + totalPages + ")"));
    }

    @Override
    public void decorate(Player player) {
        ItemStack filler = theme.filler();
        List<PluginInfo> plugins = plugin.getPluginScanner().getPlugins();

        boolean[] content = new boolean[54];
        for (int slot : CONTENT_SLOTS) {
            content[slot] = true;
        }
        for (int i = 0; i < 54; i++) {
            if (!content[i]) {
                getInventory().setItem(i, filler);
            } else {
                getInventory().setItem(i, null);
            }
        }

        addButton(4, statusButton());
        addButton(46, updateAllButton());
        addButton(49, themeToggleButton());
        addButton(52, refreshButton());
        if (page > 0) {
            addButton(45, pageButton(page - 1, "Previous Page"));
        }
        if (page < totalPages - 1) {
            addButton(53, pageButton(page + 1, "Next Page"));
        }

        int start = page * CONTENT_SLOTS.length;
        for (int i = 0; i < CONTENT_SLOTS.length; i++) {
            int index = start + i;
            if (index >= plugins.size()) {
                break;
            }
            addButton(CONTENT_SLOTS[i], pluginButton(plugins.get(index)));
        }

        super.decorate(player);
    }

    private InventoryButton statusButton() {
        return new InventoryButton().creator(player -> {
            PerformanceSnapshot snap = plugin.getPerformanceMonitor().snapshot();
            List<String> lore = new ArrayList<>();
            lore.add(theme.secondary() + "TPS: " + theme.accent() + String.format("%.1f", snap.tps1m()));
            lore.add(theme.secondary() + "MSPT: " + theme.accent() + String.format("%.2f", snap.mspt()));
            lore.add(theme.secondary() + "Memory: " + theme.accent() + snap.usedMemoryMb() + "/" + snap.maxMemoryMb() + " MB");
            lore.add(theme.secondary() + "Culled entities: " + theme.accent() + snap.culledEntities());
            lore.add(theme.secondary() + "Throttled packets: " + theme.accent() + snap.throttledPackets());
            return Items.playerHead(player, theme.primary() + "<bold>Server Status</bold>", lore);
        }).consumer(event -> {
        });
    }

    private InventoryButton themeToggleButton() {
        boolean dark = theme == Theme.DARK;
        return new InventoryButton().creator(player -> Items.create(
                dark ? "COAL" : "SUNFLOWER",
                theme.primary() + "<bold>Theme: " + (dark ? "Dark Mode" : "Light Mode") + "</bold>",
                List.of(theme.secondary() + "Click to switch to " + (dark ? "Light" : "Dark") + " Mode",
                        theme.secondary() + "Preference is saved to your profile")
        )).consumer(event -> {
            Player clicker = (Player) event.getWhoClicked();
            int currentPage = page;
            plugin.getThemeManager().toggle(clicker).thenRun(() ->
                    plugin.getChargedScheduler().runSync(() ->
                            plugin.getGuiManager().openGUI(new ManageGUI(plugin, clicker, currentPage), clicker)));
        });
    }

    private InventoryButton updateAllButton() {
        return new InventoryButton().creator(player -> Items.create(
                "ANVIL",
                theme.primary() + "<bold>Update All</bold>",
                List.of(theme.secondary() + "Queues every available update.",
                        theme.secondary() + "Updates apply on the next restart.")
        )).consumer(event -> {
            Player clicker = (Player) event.getWhoClicked();
            plugin.getUpdateManager().updateAll().thenAccept(queued ->
                    plugin.getChargedScheduler().runSync(() -> clicker.sendMessage(ColorUtil.colorize(
                            ColorUtil.PREFIX + "Queued <gold>" + queued + "</gold> plugin update(s)."))));
        });
    }

    private InventoryButton refreshButton() {
        return new InventoryButton().creator(player -> Items.create(
                "CLOCK",
                theme.primary() + "<bold>Rescan Plugins</bold>",
                List.of(theme.secondary() + "Rescans the plugins folder and",
                        theme.secondary() + "re-checks Modrinth for updates.")
        )).consumer(event -> {
            Player clicker = (Player) event.getWhoClicked();
            int currentPage = page;
            plugin.getPluginScanner().scanNow()
                    .thenCompose(v -> plugin.getPluginScanner().checkUpdates())
                    .thenRun(() -> plugin.getChargedScheduler().runSync(() ->
                            plugin.getGuiManager().openGUI(new ManageGUI(plugin, clicker, currentPage), clicker)));
        });
    }

    private InventoryButton pageButton(int targetPage, String label) {
        return new InventoryButton().creator(player -> Items.create(
                "ARROW",
                theme.primary() + "<bold>" + label + "</bold>",
                List.of(theme.secondary() + "Go to page " + (targetPage + 1))
        )).consumer(event -> {
            Player clicker = (Player) event.getWhoClicked();
            plugin.getGuiManager().openGUI(new ManageGUI(plugin, clicker, targetPage), clicker);
        });
    }

    private InventoryButton pluginButton(PluginInfo info) {
        return new InventoryButton().creator(player -> {
            String material = info.isUpdateQueued() ? "LIME_DYE"
                    : info.isUpdateAvailable() ? "ENCHANTED_BOOK" : "BOOK";
            List<String> lore = new ArrayList<>();
            lore.add(theme.secondary() + "Version: " + theme.accent() + info.getVersion());
            if (info.isUpdateQueued()) {
                lore.add("<green>Update queued — applies on restart");
            } else if (info.isUpdateAvailable()) {
                lore.add(theme.secondary() + "Latest: " + theme.accent() + info.getLatestVersion());
                lore.add("<green>Click to queue the update");
            } else {
                lore.add(theme.secondary() + "Up to date");
            }
            String description = info.getDescription();
            if (description != null && !description.isEmpty()) {
                lore.add(theme.secondary() + (description.length() > 40
                        ? description.substring(0, 40) + "..." : description));
            }
            return Items.create(material, theme.primary() + "<bold>" + info.getName() + "</bold>", lore);
        }).consumer(event -> {
            Player clicker = (Player) event.getWhoClicked();
            if (info.isUpdateQueued()) {
                clicker.sendMessage(ColorUtil.colorize(ColorUtil.PREFIX
                        + "<gold>" + info.getName() + "</gold> is already queued for update."));
                return;
            }
            if (!info.isUpdateAvailable()) {
                clicker.sendMessage(ColorUtil.colorize(ColorUtil.PREFIX
                        + "<gold>" + info.getName() + "</gold> is up to date."));
                return;
            }
            plugin.getUpdateManager().queueUpdate(info).thenAccept(success ->
                    plugin.getChargedScheduler().runSync(() -> clicker.sendMessage(ColorUtil.colorize(
                            ColorUtil.PREFIX + (success
                                    ? "Queued update for <gold>" + info.getName() + "</gold>. Applies on restart."
                                    : "<red>Failed to download update for " + info.getName() + ".")))));
        });
    }
}
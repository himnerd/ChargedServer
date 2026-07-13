package com.chargedserver.gui.impl;

import com.chargedserver.ChargedServerPlugin;
import com.chargedserver.gui.InventoryButton;
import com.chargedserver.gui.InventoryGUI;
import com.chargedserver.metrics.PerformanceSnapshot;
import com.chargedserver.pluginmanager.PluginInfo;
import com.chargedserver.theme.Theme;
import com.chargedserver.util.ColorUtil;
import com.chargedserver.util.Items;
import com.chargedserver.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class ManageGUI extends InventoryGUI {

    private final ChargedServerPlugin plugin;
    private final Player viewer;
    private final Theme theme;
    private final int page;
    private final int totalPages;
    private final int rows;
    private final int[] contentSlots;

    public ManageGUI(ChargedServerPlugin plugin, Player viewer, int page) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.theme = plugin.getThemeManager().theme(viewer.getUniqueId());
        this.rows = Math.max(4, Math.min(6,
                plugin.getConfig().getInt("gui.manage-rows", 6)));
        this.contentSlots = computeContentSlots(rows);
        int count = plugin.getPluginScanner().getPlugins().size();
        this.totalPages = Math.max(1,
                (int) Math.ceil(count / (double) contentSlots.length));
        this.page = Math.min(Math.max(page, 0), totalPages - 1);
    }

    private static int[] computeContentSlots(int rows) {
        int[] slots = new int[(rows - 2) * 7];
        int idx = 0;
        for (int row = 1; row < rows - 1; row++) {
            for (int col = 1; col <= 7; col++) {
                slots[idx++] = row * 9 + col;
            }
        }
        return slots;
    }

    @Override
    protected Inventory createInventory() {
        String titleTemplate = plugin.getConfig().getString("gui.manage-title",
                "<bold>Charged</bold> Plugin Manager ({page}/{total_pages})");
        String title = titleTemplate
                .replace("{page}", String.valueOf(page + 1))
                .replace("{total_pages}", String.valueOf(totalPages));
        return Bukkit.createInventory(null, rows * 9,
                ColorUtil.colorize(theme.primary() + title));
    }

    @Override
    public void decorate(Player player) {
        ItemStack filler = theme.filler();
        int size = rows * 9;
        List<PluginInfo> plugins = plugin.getPluginScanner().getPlugins();

        boolean[] content = new boolean[size];
        for (int slot : contentSlots) content[slot] = true;
        for (int i = 0; i < size; i++) {
            getInventory().setItem(i, content[i] ? null : filler);
        }

        int lastRow = (rows - 1) * 9;
        addButton(4, statusButton());
        addButton(lastRow + 1, updateAllButton());
        addButton(lastRow + 4, themeToggleButton());
        addButton(lastRow + 7, refreshButton());
        if (page > 0) addButton(lastRow, pageButton(page - 1, true));
        if (page < totalPages - 1) addButton(lastRow + 8, pageButton(page + 1, false));

        int start = page * contentSlots.length;
        for (int i = 0; i < contentSlots.length; i++) {
            int index = start + i;
            if (index >= plugins.size()) break;
            addButton(contentSlots[i], pluginButton(plugins.get(index)));
        }

        super.decorate(player);
    }

    private InventoryButton statusButton() {
        return new InventoryButton().creator(player -> {
            PerformanceSnapshot snap = plugin.getPerformanceMonitor().snapshot();
            List<String> lore = new ArrayList<>();
            lore.add(theme.secondary() + Messages.raw("gui-status-tps")
                    + theme.accent() + String.format("%.1f", snap.tps1m()));
            lore.add(theme.secondary() + Messages.raw("gui-status-mspt")
                    + theme.accent() + String.format("%.2f", snap.mspt()));
            lore.add(theme.secondary() + Messages.raw("gui-status-memory")
                    + theme.accent() + snap.usedMemoryMb() + "/" + snap.maxMemoryMb() + " MB");
            lore.add(theme.secondary() + Messages.raw("gui-status-culled")
                    + theme.accent() + snap.culledEntities());
            lore.add(theme.secondary() + Messages.raw("gui-status-throttled")
                    + theme.accent() + snap.throttledPackets());
            return Items.playerHead(player,
                    theme.primary() + Messages.raw("gui-status-title"), lore);
        }).consumer(event -> {
        });
    }

    private InventoryButton themeToggleButton() {
        boolean dark = theme == Theme.DARK;
        return new InventoryButton().creator(player -> Items.create(
                dark ? "COAL" : "SUNFLOWER",
                theme.primary() + Messages.raw(dark
                        ? "gui-theme-title-dark" : "gui-theme-title-light"),
                List.of(theme.secondary() + Messages.raw(dark
                                ? "gui-theme-switch-to-light"
                                : "gui-theme-switch-to-dark"),
                        theme.secondary() + Messages.raw("gui-theme-saved"))
        )).consumer(event -> {
            Player clicker = (Player) event.getWhoClicked();
            int currentPage = page;
            plugin.getThemeManager().toggle(clicker).thenRun(() ->
                    plugin.getChargedScheduler().runSync(() ->
                            plugin.getGuiManager().openGUI(
                                    new ManageGUI(plugin, clicker, currentPage),
                                    clicker)));
        });
    }

    private InventoryButton updateAllButton() {
        return new InventoryButton().creator(player -> Items.create(
                "ANVIL",
                theme.primary() + Messages.raw("gui-update-all-title"),
                List.of(theme.secondary() + Messages.raw("gui-update-all-lore-1"),
                        theme.secondary() + Messages.raw("gui-update-all-lore-2"))
        )).consumer(event -> {
            Player clicker = (Player) event.getWhoClicked();
            plugin.getUpdateManager().updateAll().thenAccept(queued ->
                    plugin.getChargedScheduler().runSync(() ->
                            clicker.sendMessage(Messages.prefixed("update-queued",
                                    "count", String.valueOf(queued)))));
        });
    }

    private InventoryButton refreshButton() {
        return new InventoryButton().creator(player -> Items.create(
                "CLOCK",
                theme.primary() + Messages.raw("gui-refresh-title"),
                List.of(theme.secondary() + Messages.raw("gui-refresh-lore-1"),
                        theme.secondary() + Messages.raw("gui-refresh-lore-2"))
        )).consumer(event -> {
            Player clicker = (Player) event.getWhoClicked();
            int currentPage = page;
            plugin.getPluginScanner().scanNow()
                    .thenCompose(v -> plugin.getPluginScanner().checkUpdates())
                    .thenRun(() -> plugin.getChargedScheduler().runSync(() ->
                            plugin.getGuiManager().openGUI(
                                    new ManageGUI(plugin, clicker, currentPage),
                                    clicker)));
        });
    }

    private InventoryButton pageButton(int targetPage, boolean previous) {
        return new InventoryButton().creator(player -> Items.create(
                "ARROW",
                theme.primary() + Messages.raw(previous
                        ? "gui-page-previous" : "gui-page-next"),
                List.of(theme.secondary() + Messages.raw("gui-page-label",
                        "page", String.valueOf(targetPage + 1)))
        )).consumer(event -> {
            Player clicker = (Player) event.getWhoClicked();
            plugin.getGuiManager().openGUI(
                    new ManageGUI(plugin, clicker, targetPage), clicker);
        });
    }

    private InventoryButton pluginButton(PluginInfo info) {
        return new InventoryButton().creator(player -> {
            String material = info.isUpdateQueued() ? "LIME_DYE"
                    : info.isUpdateAvailable() ? "ENCHANTED_BOOK" : "BOOK";
            List<String> lore = new ArrayList<>();
            lore.add(theme.secondary() + Messages.raw("gui-plugin-version")
                    + theme.accent() + info.getVersion());
            if (info.isUpdateQueued()) {
                lore.add(Messages.raw("gui-plugin-update-queued"));
            } else if (info.isUpdateAvailable()) {
                lore.add(theme.secondary() + Messages.raw("gui-plugin-latest")
                        + theme.accent() + info.getLatestVersion());
                lore.add(Messages.raw("gui-plugin-click-update"));
            } else {
                lore.add(theme.secondary() + Messages.raw("gui-plugin-up-to-date"));
            }
            String description = info.getDescription();
            if (description != null && !description.isEmpty()) {
                lore.add(theme.secondary() + (description.length() > 40
                        ? description.substring(0, 40) + "..." : description));
            }
            return Items.create(material,
                    theme.primary() + "<bold>" + info.getName() + "</bold>", lore);
        }).consumer(event -> {
            Player clicker = (Player) event.getWhoClicked();
            if (info.isUpdateQueued()) {
                clicker.sendMessage(Messages.prefixed("update-already-queued",
                        "plugin", info.getName()));
                return;
            }
            if (!info.isUpdateAvailable()) {
                clicker.sendMessage(Messages.prefixed("update-up-to-date",
                        "plugin", info.getName()));
                return;
            }
            plugin.getUpdateManager().queueUpdate(info).thenAccept(success ->
                    plugin.getChargedScheduler().runSync(() ->
                            clicker.sendMessage(Messages.prefixed(
                                    success ? "update-success" : "update-failed",
                                    "plugin", info.getName()))));
        });
    }
}
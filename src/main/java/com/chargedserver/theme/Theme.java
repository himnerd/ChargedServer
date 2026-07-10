package com.chargedserver.theme;

import com.cryptomorin.xseries.XMaterial;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * GUI palettes. Every ManageGUI resolves its theme once at open time, so
 * toggling dark mode simply reopens the GUI with the other palette — no
 * per-slot conditional logic at click time.
 */
public enum Theme {

    LIGHT("WHITE_STAINED_GLASS_PANE", "<gold>", "<gray>", "<yellow>"),
    DARK("BLACK_STAINED_GLASS_PANE", "<dark_gray>", "<gray>", "<gold>");

    private final String fillerMaterial;
    private final String primary;
    private final String secondary;
    private final String accent;

    Theme(String fillerMaterial, String primary, String secondary, String accent) {
        this.fillerMaterial = fillerMaterial;
        this.primary = primary;
        this.secondary = secondary;
        this.accent = accent;
    }

    public String primary() {
        return primary;
    }

    public String secondary() {
        return secondary;
    }

    public String accent() {
        return accent;
    }

    public ItemStack filler() {
        ItemStack item = XMaterial.matchXMaterial(fillerMaterial)
                .map(XMaterial::parseItem)
                .orElseGet(() -> new ItemStack(Material.GLASS_PANE));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            item.setItemMeta(meta);
        }
        return item;
    }
}
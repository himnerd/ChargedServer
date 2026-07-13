package com.chargedserver.theme;

import com.chargedserver.ChargedServerPlugin;
import com.cryptomorin.xseries.XMaterial;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public enum Theme {

    LIGHT("WHITE_STAINED_GLASS_PANE", "<gold>", "<gray>", "<yellow>"),
    DARK("BLACK_STAINED_GLASS_PANE", "<dark_gray>", "<gray>", "<gold>");

    private final String defaultFiller;
    private final String defaultPrimary;
    private final String defaultSecondary;
    private final String defaultAccent;

    Theme(String filler, String primary, String secondary, String accent) {
        this.defaultFiller = filler;
        this.defaultPrimary = primary;
        this.defaultSecondary = secondary;
        this.defaultAccent = accent;
    }

    private String cfg(String key, String fallback) {
        ChargedServerPlugin inst = ChargedServerPlugin.getInstance();
        if (inst == null) return fallback;
        return inst.getConfig().getString("theme." + name().toLowerCase() + "." + key, fallback);
    }

    public String primary() {
        return cfg("primary", defaultPrimary);
    }

    public String secondary() {
        return cfg("secondary", defaultSecondary);
    }

    public String accent() {
        return cfg("accent", defaultAccent);
    }

    public ItemStack filler() {
        String material = cfg("filler", defaultFiller);
        ItemStack item = XMaterial.matchXMaterial(material)
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
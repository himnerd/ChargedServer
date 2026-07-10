package com.chargedserver.util;

import com.cryptomorin.xseries.XMaterial;
import com.cryptomorin.xseries.profiles.builder.XSkull;
import com.cryptomorin.xseries.profiles.objects.Profileable;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * Utility for creating ItemStacks with MiniMessage lore and display names.
 * All text is parsed through MiniMessage so theme-aware tag prefixes work
 * transparently. Skull creation uses XSeries profiles for base64/player
 * texture support.
 */
public final class Items {

    /** Creates an item from a material name string (XMaterial-compatible). */
    public static ItemStack create(String materialName, String displayName, List<String> lore) {
        ItemStack item = XMaterial.matchXMaterial(materialName)
                .map(XMaterial::parseItem)
                .orElseGet(() -> new ItemStack(Material.STONE));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(ColorUtil.colorize(displayName));
            if (lore != null && !lore.isEmpty()) {
                meta.lore(lore.stream().map(ColorUtil::colorize).toList());
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Creates a player skull item with the given display name and lore. */
    public static ItemStack playerHead(Player player, String displayName, List<String> lore) {
        ItemStack item = XSkull.createItem()
                .profile(Profileable.of(player))
                .apply();
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(ColorUtil.colorize(displayName));
            if (lore != null && !lore.isEmpty()) {
                meta.lore(lore.stream().map(ColorUtil::colorize).toList());
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private Items() {
    }
}
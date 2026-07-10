package com.chargedserver.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * Thin wrapper around Paper's MiniMessage API. Every text string uses
 * MiniMessage tags — no legacy ChatColor — so theme swapping at runtime
 * is just changing the tag prefix (e.g. {@code <gold>} vs {@code <dark_gray>})
 * and re-parsing.
 */
public final class ColorUtil {

    public static final String PREFIX = "<bold><gradient:gold:yellow>Charged</gradient></bold> <dark_gray>»</dark_gray> ";

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    public static Component colorize(String miniMessage) {
        return MINI_MESSAGE.deserialize(miniMessage);
    }

    private ColorUtil() {
    }
}
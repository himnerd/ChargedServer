package com.chargedserver.util;

import com.chargedserver.ChargedServerPlugin;
import net.kyori.adventure.text.Component;

public final class Messages {

    private Messages() {}

    public static String prefix() {
        ChargedServerPlugin inst = ChargedServerPlugin.getInstance();
        if (inst == null) return ColorUtil.PREFIX;
        return inst.getConfig().getString("general.prefix", ColorUtil.PREFIX);
    }

    public static String raw(String key) {
        ChargedServerPlugin inst = ChargedServerPlugin.getInstance();
        if (inst == null) return "";
        return inst.getConfig().getString("messages." + key, "");
    }

    public static String raw(String key, String... pairs) {
        String msg = raw(key);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            msg = msg.replace("{" + pairs[i] + "}", pairs[i + 1]);
        }
        return msg;
    }

    public static Component get(String key, String... pairs) {
        return ColorUtil.colorize(raw(key, pairs));
    }

    public static Component prefixed(String key, String... pairs) {
        return ColorUtil.colorize(prefix() + raw(key, pairs));
    }
}
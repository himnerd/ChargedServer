package com.chargedserver.protocol;

import com.chargedserver.ChargedServerPlugin;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class PacketThrottler implements PacketListener {

    private final boolean enabled;
    private final int maxPerWindow;
    private final long windowMs;
    private final Set<String> whitelistedPackets;
    private final Set<String> throttledPackets;
    private final Map<UUID, Window> windows = new ConcurrentHashMap<>();
    private final AtomicLong throttled = new AtomicLong();

    private static final class Window {
        volatile long start;
        final AtomicInteger count = new AtomicInteger();
    }

    public PacketThrottler(ChargedServerPlugin plugin) {
        this.enabled = plugin.getConfig().getBoolean("protocol.throttle.enabled", true);
        this.maxPerWindow = plugin.getConfig().getInt("protocol.throttle.max-move-packets-per-window",
                plugin.getConfig().getInt("protocol.throttle.max-move-packets-per-tick", 20));
        this.windowMs = plugin.getConfig().getLong("protocol.throttle.window-ms", 50L);

        List<String> cfgWhitelist = plugin.getConfig().getStringList("protocol.throttle.whitelisted-packets");
        if (cfgWhitelist.isEmpty()) {
            cfgWhitelist = List.of("Experience", "SetScore", "TakeItem", "Collect",
                    "LevelUp", "SetHealth", "Attributes", "UpdateAttributes",
                    "EntityEvent", "EntityStatus", "RemoveEntities", "RemoveMob", "Bundle");
        }
        this.whitelistedPackets = new HashSet<>(cfgWhitelist);

        List<String> cfgThrottle = plugin.getConfig().getStringList("protocol.throttle.throttled-packets");
        if (cfgThrottle.isEmpty()) {
            cfgThrottle = List.of("MoveEntity", "RotateHead");
        }
        this.throttledPackets = new HashSet<>(cfgThrottle);
    }

    @Override
    public boolean onPacketSend(Player player, Object packet) {
        if (!enabled) return true;
        String name = packet.getClass().getSimpleName();

        for (String w : whitelistedPackets) {
            if (name.contains(w)) return true;
        }

        boolean shouldThrottle = false;
        for (String t : throttledPackets) {
            if (name.contains(t)) {
                shouldThrottle = true;
                break;
            }
        }
        if (!shouldThrottle) return true;

        Window window = windows.computeIfAbsent(player.getUniqueId(), key -> new Window());
        long now = System.currentTimeMillis();
        if (now - window.start > windowMs) {
            window.start = now;
            window.count.set(0);
        }
        if (window.count.incrementAndGet() > maxPerWindow) {
            throttled.incrementAndGet();
            return false;
        }
        return true;
    }

    public void clear(UUID uuid) {
        windows.remove(uuid);
    }

    public long getThrottledCount() {
        return throttled.get();
    }
}
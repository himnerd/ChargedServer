package com.chargedserver.protocol;

import com.chargedserver.ChargedServerPlugin;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drops redundant entity move/rotate packets beyond a per-player budget per
 * 50ms window. Class-name matching avoids any NMS import; the window state is
 * two primitives per player — negligible memory, no allocation per packet.
 */
public class PacketThrottler implements PacketListener {

    private static final long WINDOW_MS = 50L;

    private final boolean enabled;
    private final int maxPerWindow;
    private final Map<UUID, Window> windows = new ConcurrentHashMap<>();
    private final AtomicLong throttled = new AtomicLong();

    private static final class Window {
        volatile long start;
        final AtomicInteger count = new AtomicInteger();
    }

    public PacketThrottler(ChargedServerPlugin plugin) {
        this.enabled = plugin.getConfig().getBoolean("protocol.throttle.enabled", true);
        this.maxPerWindow = plugin.getConfig().getInt("protocol.throttle.max-move-packets-per-tick", 20);
    }

    @Override
    public boolean onPacketSend(Player player, Object packet) {
        if (!enabled) {
            return true;
        }
        String name = packet.getClass().getSimpleName();
        if (!name.contains("MoveEntity") && !name.contains("RotateHead")) {
            return true;
        }
        Window window = windows.computeIfAbsent(player.getUniqueId(), key -> new Window());
        long now = System.currentTimeMillis();
        if (now - window.start > WINDOW_MS) {
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
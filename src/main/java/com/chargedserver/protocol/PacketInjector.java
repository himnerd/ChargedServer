package com.chargedserver.protocol;

import com.chargedserver.ChargedServerPlugin;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lightweight Netty pipeline injection. The player's Channel is located by a
 * bounded reflective walk (handle -> connection -> Connection -> channel)
 * that searches by TYPE instead of field names, so it survives mapping
 * changes between Paper versions. Packet objects are passed through raw —
 * zero wrapper allocations, zero copies, no GC pressure on the hot path.
 * If injection fails on an unknown server build, the layer disables itself
 * gracefully instead of breaking logins.
 */
public class PacketInjector {

    private static final String HANDLER_NAME = "charged_packet_handler";

    private final ChargedServerPlugin plugin;
    private final List<PacketListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<UUID, Channel> channels = new ConcurrentHashMap<>();
    private final boolean enabled;
    private final int maxReflectionDepth;
    private volatile boolean broken = false;

    public PacketInjector(ChargedServerPlugin plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("protocol.enabled", true);
        this.maxReflectionDepth = plugin.getConfig().getInt("protocol.max-reflection-depth", 4);
    }

    public void registerListener(PacketListener listener) {
        listeners.add(listener);
    }

    public void unregisterListener(PacketListener listener) {
        listeners.remove(listener);
    }

    public void inject(Player player) {
        if (!enabled || broken) {
            return;
        }
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            Channel channel = findChannel(handle, 0,
                    Collections.newSetFromMap(new IdentityHashMap<>()));
            if (channel == null) {
                return;
            }
            channels.put(player.getUniqueId(), channel);
            // Pipeline mutations must happen on the channel's own event loop.
            channel.eventLoop().execute(() -> {
                ChannelPipeline pipeline = channel.pipeline();
                if (pipeline.get(HANDLER_NAME) != null) {
                    return;
                }
                if (pipeline.get("packet_handler") != null) {
                    pipeline.addBefore("packet_handler", HANDLER_NAME, new ChargedChannelHandler(player));
                } else {
                    pipeline.addLast(HANDLER_NAME, new ChargedChannelHandler(player));
                }
            });
        } catch (Throwable t) {
            broken = true;
            plugin.getLogger().warning("Packet injection unavailable on this server build: " + t.getMessage());
        }
    }

    public void uninject(UUID uuid) {
        Channel channel = channels.remove(uuid);
        if (channel != null) {
            channel.eventLoop().execute(() -> {
                if (channel.pipeline().get(HANDLER_NAME) != null) {
                    channel.pipeline().remove(HANDLER_NAME);
                }
            });
        }
    }

    public void shutdown() {
        for (UUID uuid : new ArrayList<>(channels.keySet())) {
            uninject(uuid);
        }
    }

    private Channel findChannel(Object root, int depth, Set<Object> visited) throws IllegalAccessException {
        if (root == null || depth > maxReflectionDepth || !visited.add(root)) {
            return null;
        }
        Class<?> clazz = root.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(root);
                if (value == null) {
                    continue;
                }
                if (value instanceof Channel found) {
                    return found;
                }
                String typeName = value.getClass().getName();
                // Only recurse into network-related NMS objects to keep the walk cheap.
                if (typeName.startsWith("net.minecraft") && typeName.contains("network")) {
                    Channel nested = findChannel(value, depth + 1, visited);
                    if (nested != null) {
                        return nested;
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    private final class ChargedChannelHandler extends ChannelDuplexHandler {

        private final Player player;

        private ChargedChannelHandler(Player player) {
            this.player = player;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            try {
                for (PacketListener listener : listeners) {
                    if (!listener.onPacketSend(player, msg)) {
                        ReferenceCountUtil.release(msg);
                        promise.trySuccess();
                        return;
                    }
                }
            } catch (Exception e) {
                // Don't let a bad listener break the pipeline
            }
            super.write(ctx, msg, promise);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            try {
                for (PacketListener listener : listeners) {
                    if (!listener.onPacketReceive(player, msg)) {
                        ReferenceCountUtil.release(msg);
                        return;
                    }
                }
            } catch (Exception e) {
                // Don't let a bad listener break the pipeline
            }
            super.channelRead(ctx, msg);
        }
    }
}
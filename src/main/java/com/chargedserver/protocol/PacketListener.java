package com.chargedserver.protocol;

import org.bukkit.entity.Player;

/**
 * Raw packet hook. Return false to drop the packet. Called on Netty event
 * loop threads — implementations must be non-blocking and thread-safe.
 */
public interface PacketListener {

    default boolean onPacketSend(Player player, Object packet) {
        return true;
    }

    default boolean onPacketReceive(Player player, Object packet) {
        return true;
    }
}
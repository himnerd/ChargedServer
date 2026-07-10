package com.chargedserver.account;

import lombok.Getter;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Fired on the main thread before a Bedrock-to-Java link is persisted.
 * Listeners may change the xuid/java UUID, append custom metadata, or
 * cancel the link entirely.
 */
@Getter
public class ChargedAccountLinkEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String bedrockName;
    private String xuid;
    private UUID javaUuid;
    private final Map<String, String> metadata = new HashMap<>();
    private boolean cancelled;

    public ChargedAccountLinkEvent(String bedrockName, String xuid, UUID javaUuid) {
        this.bedrockName = bedrockName;
        this.xuid = xuid;
        this.javaUuid = javaUuid;
    }

    public void setXuid(String xuid) {
        this.xuid = xuid;
    }

    public void setJavaUuid(UUID javaUuid) {
        this.javaUuid = javaUuid;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
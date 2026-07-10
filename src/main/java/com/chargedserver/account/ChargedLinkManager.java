package com.chargedserver.account;

import com.chargedserver.ChargedServerPlugin;
import org.bukkit.Bukkit;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Default LinkManager. The Java UUID lookup runs async (it may hit Mojang's
 * API for unknown names); the link event fires on the main thread; the write
 * lands on the database executor. When Floodgate is installed the real XUID
 * is resolved through its API via reflection, otherwise a deterministic
 * name-based key is used.
 */
public class ChargedLinkManager implements LinkManager {

    private final ChargedServerPlugin plugin;

    public ChargedLinkManager(ChargedServerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public CompletableFuture<Boolean> link(String bedrockName, String javaName) {
        return plugin.getChargedScheduler()
                .supplyAsync(() -> Bukkit.getOfflinePlayer(javaName).getUniqueId())
                .thenCompose(javaUuid -> {
                    CompletableFuture<Boolean> result = new CompletableFuture<>();
                    plugin.getChargedScheduler().runSync(() -> {
                        String xuid = resolveXuid(bedrockName);
                        ChargedAccountLinkEvent event = new ChargedAccountLinkEvent(bedrockName, xuid, javaUuid);
                        Bukkit.getPluginManager().callEvent(event);
                        if (event.isCancelled()) {
                            result.complete(false);
                            return;
                        }
                        plugin.getDatabaseManager()
                                .saveLink(event.getXuid(), bedrockName, event.getJavaUuid())
                                .whenComplete((v, error) -> result.complete(error == null));
                    });
                    return result;
                });
    }

    @Override
    public CompletableFuture<Optional<UUID>> getJavaUuid(String xuid) {
        return plugin.getDatabaseManager().getJavaForXuid(xuid);
    }

    @Override
    public CompletableFuture<Optional<String>> getXuid(UUID javaUuid) {
        return plugin.getDatabaseManager().getXuidForJava(javaUuid);
    }

    private String resolveXuid(String bedrockName) {
        if (Bukkit.getPluginManager().getPlugin("floodgate") != null) {
            try {
                Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                Object api = apiClass.getMethod("getInstance").invoke(null);
                Object future = apiClass.getMethod("getUuidFor", String.class).invoke(api, bedrockName);
                Object uuid = ((CompletableFuture<?>) future).getNow(null);
                if (uuid instanceof UUID bedrockUuid) {
                    // Floodgate encodes the XUID in the least significant bits.
                    return String.valueOf(Math.abs(bedrockUuid.getLeastSignificantBits()));
                }
            } catch (Throwable ignored) {
            }
        }
        return "name:" + bedrockName.toLowerCase();
    }
}
package com.chargedserver.account;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Public API for cross-platform account links. Third-party plugins can
 * listen to ChargedAccountLinkEvent to mutate payloads before persistence.
 */
public interface LinkManager {

    CompletableFuture<Boolean> link(String bedrockName, String javaName);

    CompletableFuture<Optional<UUID>> getJavaUuid(String xuid);

    CompletableFuture<Optional<String>> getXuid(UUID javaUuid);
}
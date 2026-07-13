package com.chargedserver.command;

import com.chargedserver.ChargedServerPlugin;
import com.chargedserver.backup.BackupManager;
import com.chargedserver.gui.impl.ManageGUI;
import com.chargedserver.util.ColorUtil;
import com.chargedserver.util.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.stream.Collectors;

public class ChargedCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "manage", "update", "updateall", "backup", "link", "darkmode", "status");

    private final ChargedServerPlugin plugin;

    public ChargedCommand(ChargedServerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Messages.get("plugin-info",
                    "version", plugin.getPluginMeta().getVersion()));
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "manage" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Messages.get("manage-players-only"));
                    return true;
                }
                if (!player.hasPermission("chargedserver.command.manage")) {
                    player.sendMessage(Messages.get("no-permission"));
                    return true;
                }
                plugin.getGuiManager().openGUI(new ManageGUI(plugin, player, 0), player);
            }
            case "update" -> {
                if (!sender.hasPermission("chargedserver.command.update")) {
                    sender.sendMessage(Messages.get("no-permission"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(Messages.get("update-usage"));
                    return true;
                }
                switch (args[1].toLowerCase()) {
                    case "all" -> updatePlugins(sender);
                    case "server" -> {
                        if (args.length < 3) {
                            sender.sendMessage(Messages.get("update-server-usage"));
                            return true;
                        }
                        String version = args[2];
                        boolean confirmed = args.length >= 4
                                && args[3].equalsIgnoreCase("confirm");
                        boolean allowUnstable = plugin.getConfig().getBoolean(
                                "server-updates.allow-unstable", false);
                        plugin.getUpdateManager().fetchChannel(version).thenAccept(channel -> {
                            if (channel == null) {
                                reply(sender, Messages.get("server-not-found",
                                        "version", version));
                                return;
                            }
                            boolean stable = channel.equalsIgnoreCase("default")
                                    || channel.equalsIgnoreCase("stable")
                                    || channel.equalsIgnoreCase("release");
                            reply(sender, Messages.get("server-scanning",
                                    "version", version));
                            plugin.getPluginScanner().checkCompatibilityFor(version)
                                    .thenAccept(report -> {
                                if (((!stable && !allowUnstable)
                                        || !report.incompatible().isEmpty()) && !confirmed) {
                                    StringBuilder warning = new StringBuilder();
                                    if (!stable && !allowUnstable) {
                                        warning.append(Messages.raw("server-unstable-warning",
                                                "version", version,
                                                "channel", channel.toUpperCase()))
                                                .append("\n");
                                    }
                                    if (!report.incompatible().isEmpty()) {
                                        warning.append(Messages.raw(
                                                "server-incompatible-warning",
                                                "version", version))
                                                .append("\n<red> - ")
                                                .append(String.join(", ",
                                                        report.incompatible()))
                                                .append("</red>\n");
                                    }
                                    if (!report.unknown().isEmpty()) {
                                        warning.append(Messages.raw("server-unknown-warning",
                                                "plugins", String.join(", ",
                                                        report.unknown())))
                                                .append("\n");
                                    }
                                    warning.append(Messages.raw("server-update-held",
                                            "version", version));
                                    reply(sender, ColorUtil.colorize(warning.toString()));
                                    return;
                                }
                                if (!report.unknown().isEmpty()) {
                                    reply(sender, Messages.get("server-unknown-warning",
                                            "plugins", String.join(", ",
                                                    report.unknown())));
                                }
                                runServerUpdate(sender, version);
                            });
                        });
                    }
                    default -> sender.sendMessage(Messages.get("update-usage"));
                }
            }
            case "updateall" -> {
                if (!sender.hasPermission("chargedserver.command.updateall")) {
                    sender.sendMessage(Messages.get("no-permission"));
                    return true;
                }
                updatePlugins(sender);
            }
            case "backup" -> {
                if (!sender.hasPermission("chargedserver.command.backup")) {
                    sender.sendMessage(Messages.get("no-permission"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(Messages.get("backup-usage"));
                    return true;
                }
                switch (args[1].toLowerCase()) {
                    case "now" -> {
                        sender.sendMessage(Messages.get("backup-creating"));
                        plugin.getBackupManager().backupNow("manual").thenAccept(ok -> {
                            if (!ok) {
                                reply(sender, Messages.get("backup-failed"));
                            } else if ("none".equals(
                                    plugin.getBackupManager().getProvider())) {
                                reply(sender, Messages.get("backup-complete-local"));
                            } else {
                                reply(sender, Messages.get("backup-complete-cloud",
                                        "provider",
                                        plugin.getBackupManager().getProvider()));
                            }
                        });
                    }
                    case "link" -> {
                        if (args.length < 4) {
                            sender.sendMessage(Messages.get("backup-link-usage"));
                            sender.sendMessage(Messages.get("backup-link-help"));
                            return true;
                        }
                        if (plugin.getBackupManager().link(args[2], args[3])) {
                            sender.sendMessage(Messages.get("backup-linked",
                                    "provider", args[2].toLowerCase()));
                        } else {
                            sender.sendMessage(Messages.get("backup-unknown-provider",
                                    "providers",
                                    String.join(", ", BackupManager.PROVIDERS)));
                        }
                    }
                    case "rate" -> {
                        if (args.length < 3) {
                            sender.sendMessage(Messages.get("backup-rate-usage"));
                            return true;
                        }
                        long hours;
                        try {
                            hours = Long.parseLong(args[2]);
                        } catch (NumberFormatException e) {
                            sender.sendMessage(Messages.get("backup-rate-invalid",
                                    "input", args[2]));
                            return true;
                        }
                        if (hours < 0) {
                            sender.sendMessage(Messages.get("backup-rate-negative"));
                            return true;
                        }
                        plugin.getBackupManager().setRate(hours);
                        sender.sendMessage(hours == 0
                                ? Messages.get("backup-rate-disabled")
                                : Messages.get("backup-rate-set",
                                        "hours", String.valueOf(hours)));
                    }
                    default -> sender.sendMessage(Messages.get("backup-usage"));
                }
            }
            case "link" -> {
                if (!sender.hasPermission("chargedserver.command.link")) {
                    sender.sendMessage(Messages.get("no-permission"));
                    return true;
                }
                if (!plugin.getConfig().getBoolean("account-linking.enabled", true)) {
                    sender.sendMessage(Messages.get("link-disabled"));
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(Messages.get("link-usage"));
                    return true;
                }
                plugin.getLinkManager().link(args[1], args[2])
                        .thenAccept(result -> sender.sendMessage(result
                                ? Messages.get("link-success")
                                : Messages.get("link-failed")));
            }
            case "darkmode" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Messages.get("darkmode-players-only"));
                    return true;
                }
                if (!player.hasPermission("chargedserver.command.darkmode")) {
                    player.sendMessage(Messages.get("no-permission"));
                    return true;
                }
                plugin.getThemeManager().toggle(player).thenRun(() ->
                        player.sendMessage(Messages.get("darkmode-toggled")));
            }
            case "status" -> {
                if (!sender.hasPermission("chargedserver.command.status")) {
                    sender.sendMessage(Messages.get("no-permission"));
                    return true;
                }
                var snap = plugin.getPerformanceMonitor().snapshot();
                sender.sendMessage(ColorUtil.colorize(
                        Messages.raw("status-header") + "\n"
                        + Messages.raw("status-tps", "value",
                                String.format("%.1f", snap.tps1m())) + "\n"
                        + Messages.raw("status-mspt", "value",
                                String.format("%.2f", snap.mspt())) + "\n"
                        + Messages.raw("status-memory",
                                "used", String.valueOf(snap.usedMemoryMb()),
                                "max", String.valueOf(snap.maxMemoryMb())) + "\n"
                        + Messages.raw("status-culled", "value",
                                String.valueOf(snap.culledEntities())) + "\n"
                        + Messages.raw("status-throttled", "value",
                                String.valueOf(snap.throttledPackets()))));
            }
            default -> sender.sendMessage(Messages.get("unknown-subcommand",
                    "subcommands", String.join(", ", SUBCOMMANDS)));
        }
        return true;
    }

    private void updatePlugins(CommandSender sender) {
        sender.sendMessage(Messages.get("update-backup-start"));
        plugin.getBackupManager().backupNow("pre-plugin-update").thenAccept(ok -> {
            if (!ok) {
                reply(sender, Messages.get("update-backup-failed"));
                return;
            }
            reply(sender, Messages.get("update-backup-complete"));
            plugin.getPluginScanner().checkUpdates()
                    .thenCompose(count -> plugin.getUpdateManager().updateAll())
                    .thenAccept(queued -> reply(sender,
                            Messages.get("update-plugins-complete",
                                    "count", String.valueOf(queued))));
        });
    }

    private void runServerUpdate(CommandSender sender, String version) {
        long delaySeconds = plugin.getConfig().getLong(
                "server-updates.shutdown-delay-seconds", 5);
        reply(sender, Messages.get("server-backup-start"));
        plugin.getBackupManager().backupNow("pre-server-update").thenAccept(ok -> {
            if (!ok) {
                reply(sender, Messages.get("server-backup-failed"));
                return;
            }
            reply(sender, Messages.get("server-downloading", "version", version));
            plugin.getUpdateManager().downloadServerJar(version).thenAccept(jarName -> {
                if (jarName == null) {
                    reply(sender, Messages.get("server-download-failed",
                            "version", version));
                    return;
                }
                reply(sender, Messages.get("server-downloaded", "jar", jarName));
                plugin.getPluginScanner().checkUpdatesFor(version)
                        .thenCompose(count -> plugin.getUpdateManager().updateAll())
                        .thenAccept(queued -> {
                            reply(sender, Messages.get("server-complete",
                                    "count", String.valueOf(queued),
                                    "version", version,
                                    "delay", String.valueOf(delaySeconds),
                                    "jar", jarName));
                            plugin.getChargedScheduler().runAsyncLater(() ->
                                    plugin.getChargedScheduler().runSync(
                                            Bukkit::shutdown),
                                    delaySeconds * 1000L);
                        });
            });
        });
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream()
                    .filter(s -> sender.hasPermission("chargedserver.command." + s))
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args[0].equalsIgnoreCase("update")) {
            if (args.length == 2) {
                return List.of("all", "server").stream()
                        .filter(s -> s.startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("server")) {
                List<String> versions = plugin.getUpdateManager().getCachedVersions();
                if (versions.isEmpty()) {
                    plugin.getUpdateManager().fetchVersions();
                    versions = List.of(Bukkit.getMinecraftVersion());
                }
                return versions.stream()
                        .filter(s -> s.startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
            }
            if (args.length == 4 && args[1].equalsIgnoreCase("server")) {
                return "confirm".startsWith(args[3].toLowerCase())
                        ? List.of("confirm") : List.of();
            }
        }
        if (args[0].equalsIgnoreCase("backup")) {
            if (args.length == 2) {
                return List.of("now", "link", "rate").stream()
                        .filter(s -> s.startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("link")) {
                return BackupManager.PROVIDERS.stream()
                        .filter(s -> s.startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("rate")) {
                return List.of("6", "12", "24").stream()
                        .filter(s -> s.startsWith(args[2]))
                        .collect(Collectors.toList());
            }
        }
        if ((args.length == 2 || args.length == 3)
                && args[0].equalsIgnoreCase("link")) {
            String current = args[args.length - 1].toLowerCase();
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(current))
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    private void reply(CommandSender sender, Component message) {
        if (sender instanceof Player p) {
            p.sendMessage(message);
        } else {
            Bukkit.getConsoleSender().sendMessage(message);
        }
    }
}
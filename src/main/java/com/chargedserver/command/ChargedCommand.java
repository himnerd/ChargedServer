package com.chargedserver.command;

import com.chargedserver.ChargedServerPlugin;
import com.chargedserver.gui.impl.ManageGUI;
import com.chargedserver.util.ColorUtil;
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

    private static final List<String> SUBCOMMANDS = List.of("manage", "updateall", "link", "darkmode", "status");

    private final ChargedServerPlugin plugin;

    public ChargedCommand(ChargedServerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ColorUtil.colorize(
                    "<gold>ChargedServer v" + plugin.getPluginMeta().getVersion() +
                            "</gold> — use <gold>/charged <subcommand></gold>"));
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "manage" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ColorUtil.colorize("<red>Only players can open the GUI.</red>"));
                    return true;
                }
                if (!player.hasPermission("chargedserver.command.manage")) {
                    player.sendMessage(ColorUtil.colorize("<red>No permission.</red>"));
                    return true;
                }
                plugin.getGuiManager().openGUI(new ManageGUI(plugin, player, 0), player);
            }
            case "updateall" -> {
                if (!sender.hasPermission("chargedserver.command.updateall")) {
                    sender.sendMessage(ColorUtil.colorize("<red>No permission.</red>"));
                    return true;
                }
                sender.sendMessage(ColorUtil.colorize("<yellow>Checking for updates...</yellow>"));
                plugin.getPluginScanner().checkUpdates()
                        .thenCompose(count -> plugin.getUpdateManager().updateAll())
                        .thenAccept(queued -> {
                            Component msg = ColorUtil.colorize(
                                    "<green>Queued " + queued + " plugin update(s). Applies on restart.</green>");
                            if (sender instanceof Player p) {
                                p.sendMessage(msg);
                            } else {
                                Bukkit.getConsoleSender().sendMessage(msg);
                            }
                        });
            }
            case "link" -> {
                if (!sender.hasPermission("chargedserver.command.link")) {
                    sender.sendMessage(ColorUtil.colorize("<red>No permission.</red>"));
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(ColorUtil.colorize("<red>Usage: /charged link <bedrock_name> <java_name></red>"));
                    return true;
                }
                plugin.getLinkManager().link(args[1], args[2])
                        .thenAccept(result -> sender.sendMessage(ColorUtil.colorize(
                                result ? "<green>Accounts linked successfully.</green>"
                                        : "<red>Failed to link accounts.</red>")));
            }
            case "darkmode" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ColorUtil.colorize("<red>Only players can toggle dark mode.</red>"));
                    return true;
                }
                if (!player.hasPermission("chargedserver.command.darkmode")) {
                    player.sendMessage(ColorUtil.colorize("<red>No permission.</red>"));
                    return true;
                }
                plugin.getThemeManager().toggle(player).thenRun(() ->
                        player.sendMessage(ColorUtil.colorize(
                                "<green>Dark mode toggled.</green>")));
            }
            case "status" -> {
                if (!sender.hasPermission("chargedserver.command.status")) {
                    sender.sendMessage(ColorUtil.colorize("<red>No permission.</red>"));
                    return true;
                }
                var snap = plugin.getPerformanceMonitor().snapshot();
                sender.sendMessage(ColorUtil.colorize(
                        "<gold>=== ChargedServer Status ===</gold>\n" +
                                "<gray>TPS:</gray> <yellow>" + String.format("%.1f", snap.tps1m()) + "</yellow>\n" +
                                "<gray>MSPT:</gray> <yellow>" + String.format("%.2f", snap.mspt()) + "</yellow>\n" +
                                "<gray>Memory:</gray> <yellow>" + snap.usedMemoryMb() + "/" + snap.maxMemoryMb() + " MB</yellow>\n" +
                                "<gray>Culled entities:</gray> <yellow>" + snap.culledEntities() + "</yellow>\n" +
                                "<gray>Throttled packets:</gray> <yellow>" + snap.throttledPackets() + "</yellow>"));
            }
            default -> sender.sendMessage(ColorUtil.colorize(
                    "<red>Unknown subcommand. Available: " + String.join(", ", SUBCOMMANDS) + "</red>"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
package com.chargedserver.optimization;

import com.chargedserver.ChargedServerPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stops AI ticking (Mob#setAware) for mobs beyond a configurable range from
 * every player. Entity access must stay on the main thread, so the pass runs
 * as a low-frequency sync sweep; per-mob cost is a handful of squared
 * distance checks against cached player locations.
 */
public class EntityCuller {

    private final ChargedServerPlugin plugin;
    private final Set<UUID> culled = ConcurrentHashMap.newKeySet();
    private BukkitTask task;
    private double rangeSquared;

    public EntityCuller(ChargedServerPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("optimization.entity-culling.enabled", true)) {
            return;
        }
        double range = plugin.getConfig().getDouble("optimization.entity-culling.range", 48);
        this.rangeSquared = range * range;
        long interval = plugin.getConfig().getLong("optimization.entity-culling.interval-ticks", 100);
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    }

    private void tick() {
        for (World world : Bukkit.getWorlds()) {
            List<Player> players = world.getPlayers();
            if (players.isEmpty()) {
                continue;
            }
            List<Location> locations = new ArrayList<>(players.size());
            for (Player player : players) {
                locations.add(player.getLocation());
            }
            for (Mob mob : world.getEntitiesByClass(Mob.class)) {
                Location mobLocation = mob.getLocation();
                boolean near = false;
                for (Location location : locations) {
                    if (location.distanceSquared(mobLocation) <= rangeSquared) {
                        near = true;
                        break;
                    }
                }
                if (!near) {
                    if (mob.isAware()) {
                        mob.setAware(false);
                        culled.add(mob.getUniqueId());
                    }
                } else if (culled.remove(mob.getUniqueId())) {
                    mob.setAware(true);
                }
            }
        }
    }

    public int getCulledCount() {
        return culled.size();
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
        }
        // Restore AI so a plugin reload never leaves frozen mobs behind.
        for (World world : Bukkit.getWorlds()) {
            for (Mob mob : world.getEntitiesByClass(Mob.class)) {
                if (culled.remove(mob.getUniqueId())) {
                    mob.setAware(true);
                }
            }
        }
        culled.clear();
    }
}
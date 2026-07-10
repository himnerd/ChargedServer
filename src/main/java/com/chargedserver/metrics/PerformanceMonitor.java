package com.chargedserver.metrics;

import com.chargedserver.optimization.EntityCuller;
import com.chargedserver.protocol.PacketThrottler;
import org.bukkit.Bukkit;

public class PerformanceMonitor {

    private final EntityCuller entityCuller;
    private final PacketThrottler packetThrottler;

    public PerformanceMonitor(EntityCuller entityCuller, PacketThrottler packetThrottler) {
        this.entityCuller = entityCuller;
        this.packetThrottler = packetThrottler;
    }

    /** Thread-safe: Paper's TPS/MSPT accessors read plain volatile arrays. */
    public PerformanceSnapshot snapshot() {
        double[] tps = Bukkit.getTPS();
        double mspt = Bukkit.getAverageTickTime();
        Runtime runtime = Runtime.getRuntime();
        long used = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long max = runtime.maxMemory() / (1024 * 1024);
        return new PerformanceSnapshot(
                tps.length > 0 ? tps[0] : 20.0,
                tps.length > 1 ? tps[1] : 20.0,
                tps.length > 2 ? tps[2] : 20.0,
                mspt,
                used,
                max,
                entityCuller.getCulledCount(),
                packetThrottler.getThrottledCount()
        );
    }
}
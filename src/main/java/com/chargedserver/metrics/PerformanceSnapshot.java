package com.chargedserver.metrics;

public record PerformanceSnapshot(
        double tps1m,
        double tps5m,
        double tps15m,
        double mspt,
        long usedMemoryMb,
        long maxMemoryMb,
        int culledEntities,
        long throttledPackets
) {
}
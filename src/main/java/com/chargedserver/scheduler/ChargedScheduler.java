package com.chargedserver.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Lock-free task scheduler. Async work runs on a dedicated daemon pool,
 * completely bypassing the Bukkit async scheduler. Main-thread hand-offs go
 * through a single ConcurrentLinkedQueue drained once per tick — one CAS per
 * submit, zero locks, zero per-task Bukkit task objects.
 */
public class ChargedScheduler {

    private final Plugin plugin;
    private final ScheduledThreadPoolExecutor asyncPool;
    private final ConcurrentLinkedQueue<Runnable> mainThreadQueue = new ConcurrentLinkedQueue<>();

    public ChargedScheduler(Plugin plugin) {
        this.plugin = plugin;
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        this.asyncPool = new ScheduledThreadPoolExecutor(threads, runnable -> {
            Thread thread = new Thread(runnable, "Charged-Worker");
            thread.setDaemon(true);
            return thread;
        });
        this.asyncPool.setRemoveOnCancelPolicy(true);
        // The only Bukkit task we ever register: the sync bridge drain.
        Bukkit.getScheduler().runTaskTimer(plugin, this::drainMainThreadQueue, 1L, 1L);
    }

    private void drainMainThreadQueue() {
        Runnable task;
        while ((task = mainThreadQueue.poll()) != null) {
            try {
                task.run();
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Sync task failed", t);
            }
        }
    }

    /** Runs immediately if already on the main thread, otherwise queues for next tick. */
    public void runSync(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            mainThreadQueue.add(task);
        }
    }

    public void runAsync(Runnable task) {
        asyncPool.execute(task);
    }

    public ScheduledFuture<?> runAsyncLater(Runnable task, long delayMs) {
        return asyncPool.schedule(task, delayMs, TimeUnit.MILLISECONDS);
    }

    public ScheduledFuture<?> runAsyncRepeating(Runnable task, long delayMs, long periodMs) {
        return asyncPool.scheduleAtFixedRate(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Repeating async task failed", t);
            }
        }, delayMs, periodMs, TimeUnit.MILLISECONDS);
    }

    public <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, asyncPool);
    }

    public CompletableFuture<Void> runAsyncFuture(Runnable task) {
        return CompletableFuture.runAsync(task, asyncPool);
    }

    public void shutdown() {
        asyncPool.shutdown();
        try {
            asyncPool.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        drainMainThreadQueue();
    }
}
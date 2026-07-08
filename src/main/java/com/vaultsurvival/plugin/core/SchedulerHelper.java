package com.vaultsurvival.plugin.core;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Helper utilities for Bukkit scheduler operations.
 * Provides clean wrappers around sync/async task scheduling,
 * delayed tasks, repeating tasks, and async-to-sync bridge patterns.
 */
public class SchedulerHelper {

    private final Plugin plugin;

    public SchedulerHelper(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Run a task on the main server thread (synchronously).
     */
    public BukkitTask runSync(Runnable task) {
        return Bukkit.getScheduler().runTask(plugin, task);
    }

    /**
     * Run a task asynchronously (off the main thread).
     * Use for database operations, network calls, etc.
     */
    public BukkitTask runAsync(Runnable task) {
        return Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    /**
     * Run a task on the main thread after a delay.
     */
    public BukkitTask runDelayed(Runnable task, long delayTicks) {
        return Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
    }

    /**
     * Run a repeating task on the main thread.
     */
    public BukkitTask runRepeating(Runnable task, long delayTicks, long periodTicks) {
        return Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
    }

    /**
     * Run a repeating async task.
     */
    public BukkitTask runRepeatingAsync(Runnable task, long delayTicks, long periodTicks) {
        return Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delayTicks, periodTicks);
    }

    /**
     * Run an async computation then execute the result on the main thread.
     * Useful for: async DB query -> sync UI update.
     */
    public <T> void asyncThenSync(Supplier<T> asyncWork, Consumer<T> syncCallback) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            T result = asyncWork.get();
            Bukkit.getScheduler().runTask(plugin, () -> syncCallback.accept(result));
        });
    }

    /**
     * Run an async computation with no return value, then run something sync.
     */
    public void asyncThenSync(Runnable asyncWork, Runnable syncCallback) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            asyncWork.run();
            Bukkit.getScheduler().runTask(plugin, syncCallback);
        });
    }

    /**
     * Convert a synchronous callback pattern to a CompletableFuture.
     * Runs the supplier async, then completes the future on the main thread.
     */
    public <T> CompletableFuture<T> future(Supplier<T> asyncWork) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                T result = asyncWork.get();
                Bukkit.getScheduler().runTask(plugin, () -> future.complete(result));
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> future.completeExceptionally(e));
            }
        });
        return future;
    }

    /**
     * Cancel a task if it exists.
     */
    public void cancel(BukkitTask task) {
        if (task != null && !task.isCancelled()) {
            task.cancel();
        }
    }

    /**
     * Convert seconds to ticks.
     */
    public static long secondsToTicks(long seconds) {
        return seconds * 20L;
    }

    /**
     * Convert minutes to ticks.
     */
    public static long minutesToTicks(long minutes) {
        return minutes * 60L * 20L;
    }

    /**
     * Create a simple BukkitRunnable from a lambda.
     */
    public BukkitRunnable runnable(Runnable task) {
        return new BukkitRunnable() {
            @Override
            public void run() {
                task.run();
            }
        };
    }
}

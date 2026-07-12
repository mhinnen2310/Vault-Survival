package com.vaultsurvival.plugin.core;

import java.util.concurrent.CompletableFuture;

/** Non-blocking health and metrics facade for status dialogs and diagnostics. */
public final class DatabaseHealthService {
    private final DatabaseExecutor executor;
    public DatabaseHealthService(DatabaseExecutor executor) { this.executor = executor; }
    public CompletableFuture<Boolean> check() {
        return executor.read(connection -> {
            try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT 1")) {
                return result.next() && result.getInt(1) == 1;
            }
        }).exceptionally(ignored -> false);
    }
    public DatabaseMetrics metrics() { return executor.metrics(); }
}

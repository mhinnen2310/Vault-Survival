package com.vaultsurvival.plugin.core;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bounded SQLite execution boundary. Writes are serialized by one dedicated
 * thread; reads use independent real connections and a small bounded pool.
 */
public final class DatabaseExecutor implements AutoCloseable {
    private final String jdbcUrl;
    private final Logger logger;
    private final int busyTimeoutMillis;
    private final int writeCapacity;
    private final int readCapacity;
    private final ThreadPoolExecutor writes;
    private final ThreadPoolExecutor reads;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicLong writesSubmitted = new AtomicLong();
    private final AtomicLong writesCompleted = new AtomicLong();
    private final AtomicLong writesFailed = new AtomicLong();
    private final AtomicLong writesRejected = new AtomicLong();
    private final AtomicLong writeNanos = new AtomicLong();
    private final AtomicLong longestWriteNanos = new AtomicLong();
    private final AtomicLong readsCompleted = new AtomicLong();
    private final AtomicLong readsFailed = new AtomicLong();
    private final AtomicLong readNanos = new AtomicLong();
    private final AtomicLong longestReadNanos = new AtomicLong();
    private volatile String shutdownFlushStatus = "NOT_STARTED";

    public DatabaseExecutor(String jdbcUrl, Logger logger, int busyTimeoutMillis,
                            int writeCapacity, int readThreads, int readCapacity) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl);
        this.logger = Objects.requireNonNull(logger);
        this.busyTimeoutMillis = Math.max(100, busyTimeoutMillis);
        this.writeCapacity = Math.max(16, writeCapacity);
        this.readCapacity = Math.max(16, readCapacity);
        writes = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(this.writeCapacity), factory("VS-Database-Writer"), new ThreadPoolExecutor.AbortPolicy());
        int readers = Math.max(1, Math.min(4, readThreads));
        reads = new ThreadPoolExecutor(readers, readers, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(this.readCapacity), factory("VS-Database-Reader"), new ThreadPoolExecutor.AbortPolicy());
    }

    public <T> CompletableFuture<T> write(DatabaseTransaction<T> transaction) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (!accepting.get()) return CompletableFuture.failedFuture(new RejectedExecutionException("Database is shutting down"));
        writesSubmitted.incrementAndGet();
        try {
            writes.execute(() -> executeWrite(transaction, future));
        } catch (RejectedExecutionException rejected) {
            writesRejected.incrementAndGet(); future.completeExceptionally(rejected);
        }
        return future;
    }

    public CompletableFuture<Integer> write(String sql, Object... parameters) {
        Object[] snapshot = parameters == null ? new Object[0] : parameters.clone();
        return write(connection -> {
            try (var statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < snapshot.length; i++) statement.setObject(i + 1, snapshot[i]);
                return statement.executeUpdate();
            }
        });
    }

    public <T> CompletableFuture<T> read(DatabaseTransaction<T> operation) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (!accepting.get()) return CompletableFuture.failedFuture(new RejectedExecutionException("Database is shutting down"));
        try {
            reads.execute(() -> {
                long started = System.nanoTime();
                try (Connection connection = openConnection(true)) {
                    future.complete(operation.execute(connection)); readsCompleted.incrementAndGet();
                } catch (Throwable failure) {
                    readsFailed.incrementAndGet(); future.completeExceptionally(failure);
                } finally {
                    long elapsed = System.nanoTime() - started;
                    readNanos.addAndGet(elapsed);
                    longestReadNanos.accumulateAndGet(elapsed, Math::max);
                }
            });
        } catch (RejectedExecutionException rejected) { future.completeExceptionally(rejected); }
        return future;
    }

    private <T> void executeWrite(DatabaseTransaction<T> transaction, CompletableFuture<T> future) {
        long started = System.nanoTime();
        try (Connection connection = openConnection(false)) {
            connection.setAutoCommit(false);
            try {
                T result = transaction.execute(connection);
                connection.commit(); writesCompleted.incrementAndGet(); future.complete(result);
            } catch (Throwable failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                writesFailed.incrementAndGet(); future.completeExceptionally(failure);
            }
        } catch (Throwable failure) {
            writesFailed.incrementAndGet(); future.completeExceptionally(failure);
        } finally {
            long elapsed = System.nanoTime() - started;
            writeNanos.addAndGet(elapsed);
            longestWriteNanos.accumulateAndGet(elapsed, Math::max);
        }
    }

    public Connection openConnection(boolean readOnly) throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=" + busyTimeoutMillis);
            if (readOnly) statement.execute("PRAGMA query_only=ON");
        } catch (SQLException failure) {
            try { connection.close(); } catch (SQLException ignored) { }
            throw failure;
        }
        return connection;
    }

    public DatabaseMetrics metrics() {
        long completed = writesCompleted.get(), readDone = readsCompleted.get();
        WriteQueueMetrics writeSnapshot = new WriteQueueMetrics(writes.getQueue().size(), writeCapacity,
            writesSubmitted.get(), completed, writesFailed.get(), writesRejected.get(),
            completed == 0 ? 0 : TimeUnit.NANOSECONDS.toMicros(writeNanos.get() / completed),
            TimeUnit.NANOSECONDS.toMicros(longestWriteNanos.get()));
        boolean pressure = writes.getQueue().size() >= Math.max(1, (writeCapacity * 3) / 4)
            || reads.getQueue().size() >= Math.max(1, (readCapacity * 3) / 4);
        return new DatabaseMetrics(writeSnapshot, reads.getQueue().size(), readCapacity, readDone,
            readsFailed.get(), readDone == 0 ? 0 : TimeUnit.NANOSECONDS.toMicros(readNanos.get() / readDone),
            TimeUnit.NANOSECONDS.toMicros(longestReadNanos.get()), accepting.get(), pressure, shutdownFlushStatus);
    }

    public boolean shutdown(Duration timeout) {
        shutdownFlushStatus = "DRAINING";
        accepting.set(false); writes.shutdown(); reads.shutdown();
        long millis = Math.max(1000L, timeout.toMillis());
        try {
            boolean writeStopped = writes.awaitTermination(millis, TimeUnit.MILLISECONDS);
            boolean readsStopped = reads.awaitTermination(Math.max(1000L, millis / 2), TimeUnit.MILLISECONDS);
            shutdownFlushStatus = writeStopped && readsStopped ? "DRAINED" : "TIMED_OUT";
            if (!writeStopped || !readsStopped) logger.warning("Database executor did not fully drain before shutdown timeout.");
            return writeStopped && readsStopped;
        } catch (InterruptedException interrupted) {
            shutdownFlushStatus = "INTERRUPTED";
            Thread.currentThread().interrupt(); logger.log(Level.WARNING, "Interrupted while draining database executor", interrupted); return false;
        }
    }

    @Override public void close() { shutdown(Duration.ofSeconds(30)); }

    private ThreadFactory factory(String name) {
        return runnable -> { Thread thread = new Thread(runnable, name); thread.setDaemon(false); return thread; };
    }
}

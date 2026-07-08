package com.vaultsurvival.plugin.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Transaction helper for atomic database operations.
 * Wraps common transaction patterns (begin/commit/rollback) and provides
 * functional-style APIs for safe multi-step database operations.
 *
 * Critical for: Auction Hall purchases, trade completions, cash transfers
 * where multiple tables must be updated atomically or rolled back entirely.
 */
public class TransactionHelper {

    private final DatabaseManager db;
    private final Logger logger;

    public TransactionHelper(DatabaseManager db, Logger logger) {
        this.db = db;
        this.logger = logger;
    }

    /**
     * Execute a block of work within a database transaction.
     * Automatically commits on success, rolls back on any exception.
     *
     * @param work The work to perform with the transactional connection.
     * @throws RuntimeException wrapping the original exception if work fails.
     */
    public void execute(Consumer<Connection> work) {
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                work.accept(conn);
                conn.commit();
            } catch (Exception e) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    logger.log(Level.SEVERE, "Rollback failed after transaction error", rollbackEx);
                }
                throw new RuntimeException("Transaction failed and was rolled back", e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to get connection for transaction", e);
            throw new RuntimeException("Transaction connection failed", e);
        }
    }

    /**
     * Execute a block of work within a transaction and return a result.
     *
     * @param work The work to perform with the transactional connection.
     * @return The result of the work.
     */
    public <T> T executeWithResult(Function<Connection, T> work) {
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                T result = work.apply(conn);
                conn.commit();
                return result;
            } catch (Exception e) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    logger.log(Level.SEVERE, "Rollback failed after transaction error", rollbackEx);
                }
                throw new RuntimeException("Transaction failed and was rolled back", e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to get connection for transaction", e);
            throw new RuntimeException("Transaction connection failed", e);
        }
    }

    /**
     * Execute a single UPDATE/INSERT/DELETE with parameters.
     */
    public int update(Connection conn, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            return ps.executeUpdate();
        }
    }

    /**
     * Execute a SELECT and process results with a row handler.
     */
    public void query(Connection conn, String sql, Consumer<ResultSet> rowHandler, Object... params)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rowHandler.accept(rs);
                }
            }
        }
    }

    /**
     * Execute a SELECT and return the first matching result.
     */
    public <T> T querySingle(Connection conn, String sql, Function<ResultSet, T> mapper, T defaultValue,
                              Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapper.apply(rs);
                }
            }
        }
        return defaultValue;
    }
}

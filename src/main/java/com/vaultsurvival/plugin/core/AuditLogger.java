package com.vaultsurvival.plugin.core;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Central audit logger for all important server actions.
 * Every cash creation, vault breach, trade, arrest, admin action, etc.
 * must be written to the admin_audit_log table for debuggability.
 */
public class AuditLogger {

    private final Logger logger;
    private final DatabaseManager db;
    private record AuditEvent(String actorUuid,String actorName,String actionType,String targetType,String targetId,String details) { }
    private final ArrayBlockingQueue<AuditEvent> pending = new ArrayBlockingQueue<>(8192);
    private final ScheduledExecutorService flusher;
    private final AtomicBoolean flushing = new AtomicBoolean();

    public AuditLogger(Logger logger, DatabaseManager db) {
        this.logger = logger;
        this.db = db;
        this.flusher = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "VS-Audit-Batcher"); t.setDaemon(false); return t; });
        this.flusher.scheduleWithFixedDelay(this::flush, 100, 100, TimeUnit.MILLISECONDS);
    }

    /**
     * Log an auditable action to the database.
     *
     * @param actorUuid   UUID of the player performing the action (can be null for system actions)
     * @param actorName   Username of the actor
     * @param actionType  Type of action (e.g. "CASH_CREATE", "VAULT_BREACH", "TRADE_COMPLETE")
     * @param targetType  Type of target (e.g. "PLAYER", "VAULT", "LISTING")
     * @param targetId    ID of the target (UUID or other identifier as string)
     * @param details     Human-readable description or JSON details
     */
    public void log(UUID actorUuid, String actorName, String actionType,
                    String targetType, String targetId, String details) {
        AuditEvent event = new AuditEvent(actorUuid == null ? null : actorUuid.toString(), actorName, actionType, targetType, targetId, details);
        if (!pending.offer(event)) {
            logger.severe("Audit queue is full; forcing an immediate database flush for " + actionType);
            flush();
            if (!pending.offer(event)) logger.severe("Audit event could not be queued after flush: " + actionType);
        }
    }

    public void flush() {
        if (!flushing.compareAndSet(false, true)) return;
        List<AuditEvent> batch = new ArrayList<>(Math.min(256, pending.size())); pending.drainTo(batch, 256);
        if (batch.isEmpty()) { flushing.set(false); return; }
        db.write(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO admin_audit_log(actor_uuid,actor_name,action_type,target_type,target_id,details) VALUES(?,?,?,?,?,?)")) {
                for (AuditEvent event : batch) {
                    statement.setString(1,event.actorUuid()); statement.setString(2,event.actorName()); statement.setString(3,event.actionType());
                    statement.setString(4,event.targetType()); statement.setString(5,event.targetId()); statement.setString(6,event.details()); statement.addBatch();
                }
                statement.executeBatch(); return batch.size();
            }
        }).whenComplete((ignored, failure) -> {
            if (failure != null) {
                logger.log(Level.SEVERE, "Failed to flush " + batch.size() + " audit events", failure);
                for (AuditEvent event : batch) if (!pending.offer(event)) logger.severe("Audit recovery queue overflow for " + event.actionType());
            }
            flushing.set(false); if (!pending.isEmpty()) flush();
        });
    }

    public void shutdown() {
        flusher.shutdown();
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while ((!pending.isEmpty() || flushing.get()) && System.nanoTime() < deadline) {
            flush();
            try { Thread.sleep(10); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
        try { flusher.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        if (!pending.isEmpty()) logger.severe("Audit shutdown left " + pending.size() + " events pending.");
    }

    /**
     * Convenience method for system/automated actions with no player actor.
     */
    public void logSystem(String actionType, String targetType, String targetId, String details) {
        log(null, "SYSTEM", actionType, targetType, targetId, details);
    }

    // --- Specific audit methods for common actions ---

    public void logCashCreate(UUID actorUuid, String actorName, UUID cashUuid, long amount, String source) {
        log(actorUuid, actorName, "CASH_CREATE", "CASH", cashUuid.toString(),
            "amount=" + amount + " source=" + source);
    }

    public void logCashSplit(UUID actorUuid, String actorName, UUID parentUuid, long amount, String reason) {
        log(actorUuid, actorName, "CASH_SPLIT", "CASH", parentUuid.toString(),
            "split_amount=" + amount + " reason=" + reason);
    }

    public void logCashMerge(UUID actorUuid, String actorName, UUID targetUuid, long totalAmount) {
        log(actorUuid, actorName, "CASH_MERGE", "CASH", targetUuid.toString(),
            "total_amount=" + totalAmount);
    }

    public void logCashInvalidate(UUID actorUuid, String actorName, UUID cashUuid, String reason) {
        log(actorUuid, actorName, "CASH_INVALIDATE", "CASH", cashUuid.toString(), reason);
    }

    public void logCashPickup(UUID playerUuid, String playerName, UUID cashUuid, long amount) {
        log(playerUuid, playerName, "CASH_PICKUP", "CASH", cashUuid.toString(),
            "amount=" + amount);
    }

    public void logCashDrop(UUID playerUuid, String playerName, UUID cashUuid, long amount) {
        log(playerUuid, playerName, "CASH_DROP", "CASH", cashUuid.toString(),
            "amount=" + amount);
    }

    public void logVaultDeposit(UUID playerUuid, String playerName, UUID vaultUuid, UUID cashUuid, long amount) {
        log(playerUuid, playerName, "VAULT_DEPOSIT", "VAULT", vaultUuid.toString(),
            "cash=" + cashUuid + " amount=" + amount);
    }

    public void logVaultWithdraw(UUID playerUuid, String playerName, UUID vaultUuid, UUID cashUuid, long amount) {
        log(playerUuid, playerName, "VAULT_WITHDRAW", "VAULT", vaultUuid.toString(),
            "cash=" + cashUuid + " amount=" + amount);
    }

    public void logVaultBreach(UUID thiefUuid, String thiefName, UUID vaultUuid,
                                boolean success, long stolenAmount, long balanceBefore, long balanceAfter) {
        log(thiefUuid, thiefName, "VAULT_BREACH", "VAULT", vaultUuid.toString(),
            "success=" + success + " stolen=" + stolenAmount +
            " before=" + balanceBefore + " after=" + balanceAfter);
    }

    public void logAuctionCreate(UUID sellerUuid, String sellerName, UUID listingUuid, long price) {
        log(sellerUuid, sellerName, "AH_LISTING_CREATE", "LISTING", listingUuid.toString(),
            "price=" + price);
    }

    public void logAuctionPurchase(UUID buyerUuid, String buyerName, UUID listingUuid,
                                    UUID sellerUuid, long price, long tax) {
        log(buyerUuid, buyerName, "AH_PURCHASE", "LISTING", listingUuid.toString(),
            "seller=" + sellerUuid + " price=" + price + " tax=" + tax);
    }

    public void logAuctionLockerWithdraw(UUID playerUuid, String playerName, long amount) {
        log(playerUuid, playerName, "AH_LOCKER_WITHDRAW", "LOCKER", playerUuid.toString(),
            "amount=" + amount);
    }

    public void logTradeComplete(UUID player1, String name1, UUID player2, String name2, String details) {
        log(player1, name1, "TRADE_COMPLETE", "TRADE", player2.toString(), details);
    }

    public void logDistrictTreasuryDeposit(UUID actorUuid, String actorName, UUID districtUuid, long amount) {
        log(actorUuid, actorName, "DISTRICT_TREASURY_DEPOSIT", "DISTRICT", districtUuid.toString(),
            "amount=" + amount);
    }

    public void logDistrictTreasuryWithdraw(UUID actorUuid, String actorName, UUID districtUuid, long amount) {
        log(actorUuid, actorName, "DISTRICT_TREASURY_WITHDRAW", "DISTRICT", districtUuid.toString(),
            "amount=" + amount);
    }

    public void logRestoration(UUID districtUuid, int blocksRestored, int pointsUsed) {
        logSystem("RESTORATION", "DISTRICT", districtUuid.toString(),
            "blocks_restored=" + blocksRestored + " points_used=" + pointsUsed);
    }

    public void logPoliceArrest(UUID officerUuid, String officerName, UUID criminalUuid, String reason) {
        log(officerUuid, officerName, "POLICE_ARREST", "PLAYER", criminalUuid.toString(), reason);
    }

    public void logBountyClaim(UUID hunterUuid, String hunterName, UUID targetUuid, long amount) {
        log(hunterUuid, hunterName, "BOUNTY_CLAIM", "BOUNTY", targetUuid.toString(),
            "amount=" + amount);
    }

    public void logAdminAction(UUID adminUuid, String adminName, String action, String target, String details) {
        log(adminUuid, adminName, "ADMIN_" + action, "SYSTEM", target, details);
    }
}

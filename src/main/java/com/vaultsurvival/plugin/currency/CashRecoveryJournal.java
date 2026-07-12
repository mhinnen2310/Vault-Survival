package com.vaultsurvival.plugin.currency;

import com.vaultsurvival.plugin.core.DatabaseManager;
import com.vaultsurvival.plugin.core.DatabaseExecutor;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Durable recovery journal. Critical entries use the serialized writer and are never fire-and-forget. */
public final class CashRecoveryJournal {
    private final DatabaseExecutor database;
    public CashRecoveryJournal(DatabaseManager database) { this(database.executor()); }
    public CashRecoveryJournal(DatabaseExecutor database) { this.database=database; }

    public CompletableFuture<CashTransactionResult> prepare(CashPaymentPlan plan) {
        return database.write(connection -> {
            try (PreparedStatement existing=connection.prepareStatement("SELECT transaction_uuid,state,requested_amount,change_amount,failure_reason FROM cash_transaction_journal WHERE idempotency_key=?")) {
                existing.setString(1,plan.idempotencyKey()); try(var rs=existing.executeQuery()) { if(rs.next()) return new CashTransactionResult(UUID.fromString(rs.getString(1)),plan.idempotencyKey(),CashTransactionState.valueOf(rs.getString(2)),rs.getLong(3),rs.getLong(4),rs.getString(5),true); }
            }
            try (PreparedStatement insert=connection.prepareStatement("INSERT INTO cash_transaction_journal(transaction_uuid,idempotency_key,player_uuid,state,requested_amount,change_amount,destination_type,destination_id,plan_data,created_at,updated_at,recovery_attempts) VALUES(?,?,?,?,?,?,?,?,?,?,?,0)")) {
                long now=System.currentTimeMillis(); insert.setString(1,plan.transactionUuid().toString());insert.setString(2,plan.idempotencyKey());insert.setString(3,plan.playerUuid().toString());insert.setString(4,CashTransactionState.PREPARED.name());insert.setLong(5,plan.requestedAmount());insert.setLong(6,plan.returnedChange());insert.setString(7,plan.destinationType());insert.setString(8,plan.destinationId());insert.setString(9,encode(plan));insert.setLong(10,now);insert.setLong(11,now);insert.executeUpdate();
            }
            return new CashTransactionResult(plan.transactionUuid(),plan.idempotencyKey(),CashTransactionState.PREPARED,plan.requestedAmount(),plan.returnedChange(),null,false);
        });
    }

    public CompletableFuture<Void> transition(UUID transaction, CashTransactionState expected, CashTransactionState next, String failure) {
        return database.write(connection -> { try(PreparedStatement ps=connection.prepareStatement("UPDATE cash_transaction_journal SET state=?,failure_reason=?,updated_at=?,recovery_attempts=recovery_attempts+CASE WHEN ?='RECOVERY_REQUIRED' THEN 1 ELSE 0 END WHERE transaction_uuid=? AND state=?")) { ps.setString(1,next.name());ps.setString(2,failure);ps.setLong(3,System.currentTimeMillis());ps.setString(4,next.name());ps.setString(5,transaction.toString());ps.setString(6,expected.name());if(ps.executeUpdate()!=1) throw new IllegalStateException("Cash journal phase changed concurrently for "+transaction); } return null; });
    }

    private static String encode(CashPaymentPlan plan) {
        return plan.lines().stream().map(l -> l.slot()+":"+l.cashUuid()+":"+l.pdcAmount()+":"+l.databaseAmount()+":"+l.consumedAmount()+":"+l.splitAmount()+":"+l.changeCashUuid()+":"+l.validatedState()).reduce((a,b)->a+","+b).orElse("");
    }
}

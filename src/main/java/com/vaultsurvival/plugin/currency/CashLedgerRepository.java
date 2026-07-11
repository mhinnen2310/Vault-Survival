package com.vaultsurvival.plugin.currency;

import com.vaultsurvival.plugin.core.DatabaseManager;
import com.vaultsurvival.plugin.core.DatabaseExecutor;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** All authoritative cash/journal access is routed through DatabaseExecutor. */
public final class CashLedgerRepository {
    private final DatabaseExecutor database;
    public CashLedgerRepository(DatabaseManager database) { this(database.executor()); }
    public CashLedgerRepository(DatabaseExecutor database) { this.database = Objects.requireNonNull(database); }

    public CompletableFuture<Map<UUID, CashRecord>> load(Collection<UUID> ids) {
        List<UUID> copy = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (copy.isEmpty()) return CompletableFuture.completedFuture(Map.of());
        return database.read(connection -> {
            String marks = String.join(",", Collections.nCopies(copy.size(), "?"));
            Map<UUID, CashRecord> result = new HashMap<>();
            try (PreparedStatement ps = connection.prepareStatement("SELECT cash_uuid,amount,state,location_type,location_id,current_holder,created_by FROM cash_items WHERE cash_uuid IN ("+marks+")")) {
                for (int i=0;i<copy.size();i++) ps.setString(i+1, copy.get(i).toString());
                try (ResultSet rs=ps.executeQuery()) { while(rs.next()) {
                    UUID id=UUID.fromString(rs.getString(1)); String holder=rs.getString(6), issuer=rs.getString(7);
                    result.put(id,new CashRecord(id,rs.getLong(2),CashItemData.CashState.valueOf(rs.getString(3)),rs.getString(4),rs.getString(5),holder==null?null:UUID.fromString(holder),issuer==null?null:UUID.fromString(issuer)));
                }}
            }
            return Map.copyOf(result);
        });
    }

    public CompletableFuture<Optional<CashTransactionResult>> findByIdempotencyKey(String key) {
        return database.read(connection -> {
            try (PreparedStatement ps=connection.prepareStatement("SELECT transaction_uuid,state,requested_amount,change_amount,failure_reason FROM cash_transaction_journal WHERE idempotency_key=?")) {
                ps.setString(1,key); try(ResultSet rs=ps.executeQuery()) { if(!rs.next()) return Optional.empty();
                    return Optional.of(new CashTransactionResult(UUID.fromString(rs.getString(1)),key,CashTransactionState.valueOf(rs.getString(2)),rs.getLong(3),rs.getLong(4),rs.getString(5),true)); }
            }
        });
    }

    public CompletableFuture<CashTransactionResult> commit(CashPaymentPlan plan){
        return database.write(connection->{
            try(PreparedStatement state=connection.prepareStatement("SELECT state FROM cash_transaction_journal WHERE transaction_uuid=?")){state.setString(1,plan.transactionUuid().toString());try(ResultSet rs=state.executeQuery()){if(!rs.next())throw new IllegalStateException("Cash transaction was not prepared");CashTransactionState current=CashTransactionState.valueOf(rs.getString(1));if(current!=CashTransactionState.PREPARED)return new CashTransactionResult(plan.transactionUuid(),plan.idempotencyKey(),current,plan.requestedAmount(),plan.returnedChange(),null,true);}}
            for(CashPaymentPlan.Line line:plan.lines()){
                try(PreparedStatement spend=connection.prepareStatement("UPDATE cash_items SET state='SPENT',location_type='TRANSACTION',location_id=?,last_seen_at=datetime('now') WHERE cash_uuid=? AND amount=? AND state IN ('ACTIVE','DROPPED') AND location_type=? AND location_id=?")){spend.setString(1,plan.transactionUuid().toString());spend.setString(2,line.cashUuid().toString());spend.setLong(3,line.databaseAmount());spend.setString(4,line.validatedLocationType());spend.setString(5,line.validatedLocationId());if(spend.executeUpdate()!=1)throw new IllegalStateException("Cash ledger changed before commit: "+line.cashUuid());}
                insertLedger(connection,plan,line.cashUuid(),"CONSUME",line.consumedAmount(),line.validatedLocationType(),line.validatedLocationId(),plan.destinationType(),plan.destinationId());
                if(line.splitAmount()>0){try(PreparedStatement change=connection.prepareStatement("INSERT INTO cash_items(cash_uuid,amount,state,created_at,last_seen_at,location_type,location_id,owner_uuid,created_by,issued_by,original_owner,current_holder) VALUES(?,?,'ACTIVE',datetime('now'),datetime('now'),'TRANSACTION',?,?,?,?,?,?)")){change.setString(1,line.changeCashUuid().toString());change.setLong(2,line.splitAmount());change.setString(3,plan.transactionUuid().toString());change.setString(4,plan.playerUuid().toString());change.setString(5,plan.playerUuid().toString());change.setString(6,plan.playerUuid().toString());change.setString(7,plan.playerUuid().toString());change.setString(8,plan.playerUuid().toString());change.executeUpdate();}insertLedger(connection,plan,line.changeCashUuid(),"CHANGE",line.splitAmount(),"TRANSACTION",plan.transactionUuid().toString(),"INVENTORY",plan.playerUuid().toString());}
            }
            try(PreparedStatement update=connection.prepareStatement("UPDATE cash_transaction_journal SET state='LEDGER_COMMITTED',updated_at=? WHERE transaction_uuid=? AND state='PREPARED'")){update.setLong(1,System.currentTimeMillis());update.setString(2,plan.transactionUuid().toString());if(update.executeUpdate()!=1)throw new IllegalStateException("Cash journal commit race");}
            return new CashTransactionResult(plan.transactionUuid(),plan.idempotencyKey(),CashTransactionState.LEDGER_COMMITTED,plan.requestedAmount(),plan.returnedChange(),null,false);
        });
    }

    public CompletableFuture<Void> completeInventory(CashPaymentPlan plan){return database.write(connection->{for(var line:plan.lines())if(line.changeCashUuid()!=null)try(PreparedStatement ps=connection.prepareStatement("UPDATE cash_items SET location_type='INVENTORY',location_id=?,current_holder=?,owner_uuid=?,last_seen_at=datetime('now') WHERE cash_uuid=? AND location_type='TRANSACTION' AND location_id=?")){ps.setString(1,plan.playerUuid().toString());ps.setString(2,plan.playerUuid().toString());ps.setString(3,plan.playerUuid().toString());ps.setString(4,line.changeCashUuid().toString());ps.setString(5,plan.transactionUuid().toString());if(ps.executeUpdate()!=1)throw new IllegalStateException("Change cash recovery location mismatch");}try(PreparedStatement ps=connection.prepareStatement("UPDATE cash_transaction_journal SET state='COMPLETED',updated_at=? WHERE transaction_uuid=? AND state='LEDGER_COMMITTED'")){ps.setLong(1,System.currentTimeMillis());ps.setString(2,plan.transactionUuid().toString());if(ps.executeUpdate()!=1)throw new IllegalStateException("Cash journal completion race");}return null;});}

    private static void insertLedger(java.sql.Connection connection,CashPaymentPlan plan,UUID cashUuid,String type,long amount,String sourceType,String sourceId,String destinationType,String destinationId)throws Exception{try(PreparedStatement ps=connection.prepareStatement("INSERT INTO cash_transaction_ledger(transaction_uuid,cash_uuid,entry_type,amount,source_location_type,source_location_id,destination_location_type,destination_location_id,created_at) VALUES(?,?,?,?,?,?,?,?,?)")){ps.setString(1,plan.transactionUuid().toString());ps.setString(2,cashUuid.toString());ps.setString(3,type);ps.setLong(4,amount);ps.setString(5,sourceType);ps.setString(6,sourceId);ps.setString(7,destinationType);ps.setString(8,destinationId);ps.setLong(9,System.currentTimeMillis());ps.executeUpdate();}}
}

package com.vaultsurvival.plugin.currency;

import com.vaultsurvival.plugin.core.DatabaseExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class CashTransactionCoordinatorTest {
    @TempDir Path temp;
    private DatabaseExecutor database;
    private CashTransactionCoordinator coordinator;
    private CashLedgerRepository ledger;
    private UUID player;

    @BeforeEach void setUp() throws Exception {
        String url="jdbc:sqlite:"+temp.resolve("cash.db");
        try(var connection=DriverManager.getConnection(url);var statement=connection.createStatement()) {
            statement.execute("CREATE TABLE cash_items(id INTEGER PRIMARY KEY AUTOINCREMENT,cash_uuid TEXT UNIQUE NOT NULL,amount INTEGER NOT NULL,state TEXT NOT NULL,created_at TEXT,last_seen_at TEXT,location_type TEXT,location_id TEXT,owner_uuid TEXT,created_by TEXT,issued_by TEXT,original_owner TEXT,current_holder TEXT)");
            statement.execute("CREATE TABLE cash_transaction_journal(id INTEGER PRIMARY KEY AUTOINCREMENT,transaction_uuid TEXT UNIQUE NOT NULL,idempotency_key TEXT UNIQUE NOT NULL,player_uuid TEXT NOT NULL,state TEXT NOT NULL,requested_amount INTEGER NOT NULL,change_amount INTEGER NOT NULL,destination_type TEXT NOT NULL,destination_id TEXT,plan_data TEXT NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,recovery_attempts INTEGER NOT NULL DEFAULT 0,failure_reason TEXT)");
            statement.execute("CREATE TABLE cash_transaction_ledger(id INTEGER PRIMARY KEY AUTOINCREMENT,transaction_uuid TEXT NOT NULL,cash_uuid TEXT NOT NULL,entry_type TEXT NOT NULL,amount INTEGER NOT NULL,source_location_type TEXT,source_location_id TEXT,destination_location_type TEXT,destination_location_id TEXT,created_at INTEGER NOT NULL,UNIQUE(transaction_uuid,cash_uuid,entry_type))");
            statement.execute("CREATE TABLE cash_recovery_deliveries(id INTEGER PRIMARY KEY AUTOINCREMENT,transaction_uuid TEXT NOT NULL,cash_uuid TEXT NOT NULL,player_uuid TEXT NOT NULL,amount INTEGER NOT NULL,state TEXT NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,failure_reason TEXT,UNIQUE(transaction_uuid,cash_uuid))");
            statement.execute("CREATE TABLE district_treasury_vaults(vault_uuid TEXT PRIMARY KEY,district_id INTEGER NOT NULL)");
        }
        database=new DatabaseExecutor(url, Logger.getLogger("cash-test"),1000,64,2,64);
        ledger=new CashLedgerRepository(database);
        coordinator=new CashTransactionCoordinator(ledger,new CashRecoveryJournal(database));
        player=UUID.randomUUID();
    }

    @AfterEach void tearDown(){ assertTrue(database.shutdown(Duration.ofSeconds(5))); }

    private UUID cash(long amount, UUID holder, String locationId) {
        UUID id=UUID.randomUUID();
        database.write(c->{try(var ps=c.prepareStatement("INSERT INTO cash_items(cash_uuid,amount,state,location_type,location_id,current_holder,owner_uuid,created_by) VALUES(?,?,'ACTIVE','INVENTORY',?,?,?,?)")){ps.setString(1,id.toString());ps.setLong(2,amount);ps.setString(3,locationId);ps.setString(4,holder.toString());ps.setString(5,holder.toString());ps.setString(6,UUID.randomUUID().toString());ps.executeUpdate();}return null;}).join();
        return id;
    }

    private CashInventorySnapshot snapshot(UUID id,long pdc,int slot,int empty){return new CashInventorySnapshot(player,List.of(new CashInventorySnapshot.Entry(slot,id,pdc)),empty,System.currentTimeMillis());}

    @Test void exactPaymentUsesExactSlotAndUuid(){UUID id=cash(100,player,player.toString());var plan=coordinator.plan(snapshot(id,100,4,2),100,"exact","TREASURY","1").join();assertEquals(0,plan.returnedChange());assertEquals(4,plan.lines().getFirst().slot());assertEquals(id,plan.lines().getFirst().cashUuid());}
    @Test void paymentWithChangeCreatesSplitInstruction(){UUID id=cash(100,player,player.toString());var plan=coordinator.plan(snapshot(id,100,1,0),60,"change","SHOP","4").join();assertEquals(40,plan.returnedChange());assertEquals(40,plan.lines().getFirst().splitAmount());}
    @Test void modifiedPdcAmountIsRejected(){UUID id=cash(100,player,player.toString());assertThrows(CompletionException.class,()->coordinator.plan(snapshot(id,99,0,1),50,"modified","SHOP","4").join());}
    @Test void invalidCashUuidIsRejected(){assertThrows(CompletionException.class,()->coordinator.plan(snapshot(UUID.randomUUID(),50,0,1),50,"unknown","SHOP","4").join());}
    @Test void stalePhysicalLocationIsRejected(){UUID id=cash(100,player,"SOMEONE_ELSE");assertThrows(CompletionException.class,()->coordinator.plan(snapshot(id,100,0,1),50,"stale","SHOP","4").join());}
    @Test void bearerCashCanBeSpentByPhysicalHolderEvenWhenHistoricalHolderDiffers(){UUID id=cash(100,UUID.randomUUID(),player.toString());var plan=coordinator.plan(snapshot(id,100,0,1),100,"bearer","SHOP","4").join();assertEquals(100,plan.requestedAmount());}
    @Test void repeatedIdempotencyKeyReturnsOriginalTransaction(){UUID id=cash(100,player,player.toString());var plan=coordinator.plan(snapshot(id,100,0,1),100,"same-click","SHOP","4").join();var first=coordinator.prepare(plan).join();var second=coordinator.prepare(new CashPaymentPlan(UUID.randomUUID(),"same-click",player,plan.lines(),100,0,"SHOP","4",List.of())).join();assertEquals(first.transactionUuid(),second.transactionUuid());assertTrue(second.replayed());}
    @Test void restartCanReadPreparedJournalDeterministically(){UUID id=cash(100,player,player.toString());var plan=coordinator.plan(snapshot(id,100,0,1),100,"restart","SHOP","4").join();coordinator.prepare(plan).join();assertEquals(plan.transactionUuid(),ledger.findByIdempotencyKey("restart").join().orElseThrow().transactionUuid());}
    @Test void ledgerCommitCreatesExactChangeAndBalances(){UUID id=cash(100,player,player.toString());var plan=coordinator.plan(snapshot(id,100,2,1),60,"commit-change","SHOP","4").join();coordinator.prepare(plan).join();var result=coordinator.commitLedger(plan).join();assertEquals(CashTransactionState.LEDGER_COMMITTED,result.state());database.read(c->{try(var ps=c.prepareStatement("SELECT amount,state,location_type FROM cash_items WHERE cash_uuid=?")){ps.setString(1,plan.lines().getFirst().changeCashUuid().toString());try(var r=ps.executeQuery()){assertTrue(r.next());assertEquals(40,r.getLong(1));assertEquals("ACTIVE",r.getString(2));assertEquals("TRANSACTION",r.getString(3));}}return null;}).join();}
    @Test void businessFailureRollsBackCashAndJournal(){UUID id=cash(100,player,player.toString());var plan=coordinator.plan(snapshot(id,100,0,1),100,"business-fail","SHOP","4").join();coordinator.prepare(plan).join();assertThrows(CompletionException.class,()->coordinator.commitLedger(plan,(c,p)->{throw new IllegalStateException("stock race");}).join());database.read(c->{try(var ps=c.prepareStatement("SELECT state FROM cash_items WHERE cash_uuid=?")){ps.setString(1,id.toString());try(var r=ps.executeQuery()){assertTrue(r.next());assertEquals("ACTIVE",r.getString(1));}}return null;}).join();}
    @Test void storedTreasuryPaymentUsesCentralLedger(){UUID vault=UUID.randomUUID(),cash=UUID.randomUUID();database.write(c->{try(var v=c.prepareStatement("INSERT INTO district_treasury_vaults VALUES(?,7)")){v.setString(1,vault.toString());v.executeUpdate();}try(var p=c.prepareStatement("INSERT INTO cash_items(cash_uuid,amount,state,location_type,location_id) VALUES(?,250,'IN_DISTRICT_TREASURY','DISTRICT_TREASURY_VAULT',?)")){p.setString(1,cash.toString());p.setString(2,vault.toString());p.executeUpdate();}return null;}).join();var plan=coordinator.planDistrictTreasury(player,7,100,"facility-7","FACILITY","TOWN_HALL").join();coordinator.prepare(plan).join();var result=coordinator.commitLedger(plan).join();assertEquals(CashTransactionState.COMPLETED,result.state());assertEquals(150,plan.returnedChange());}
}

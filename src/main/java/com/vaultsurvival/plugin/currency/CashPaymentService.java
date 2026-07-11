package com.vaultsurvival.plugin.currency;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;

/** Main-thread snapshot facade for the central physical-cash coordinator. */
public final class CashPaymentService {
    private final com.vaultsurvival.plugin.VaultSurvivalPlugin plugin;
    private final CurrencyService currency; private final CashTransactionCoordinator coordinator;
    public CashPaymentService(com.vaultsurvival.plugin.VaultSurvivalPlugin plugin,CurrencyService currency,CashTransactionCoordinator coordinator){this.plugin=plugin;this.currency=currency;this.coordinator=coordinator;}

    public CashInventorySnapshot capture(Player player) {
        if(!player.getServer().isPrimaryThread()) throw new IllegalStateException("Cash inventory snapshots must be captured on the Paper main thread");
        List<CashInventorySnapshot.Entry> entries=new ArrayList<>(); int empty=0;
        ItemStack[] contents=player.getInventory().getStorageContents();
        for(int slot=0;slot<contents.length;slot++){ItemStack item=contents[slot];if(item==null||item.getType().isAir()){empty++;continue;}CashSnapshot cash=currency.snapshot(item);if(cash!=null)entries.add(new CashInventorySnapshot.Entry(slot,cash.cashUuid(),cash.itemAmount()));}
        return new CashInventorySnapshot(player.getUniqueId(),entries,empty,System.currentTimeMillis());
    }

    public CompletableFuture<CashPaymentPlan> plan(CashInventorySnapshot snapshot,long amount,String idempotencyKey,String destinationType,String destinationId){return coordinator.plan(snapshot,amount,idempotencyKey,destinationType,destinationId);}
    public CompletableFuture<CashTransactionResult> prepare(CashPaymentPlan plan){return coordinator.prepare(plan);}

    /** Commit ledger off-thread, then revalidate and apply inventory on the main thread. */
    public CompletableFuture<CashTransactionResult> execute(Player player,CashPaymentPlan plan){
        if(!Bukkit.isPrimaryThread())return CompletableFuture.failedFuture(new IllegalStateException("execute must start on the Paper main thread"));
        CompletableFuture<CashTransactionResult> result=new CompletableFuture<>();
        coordinator.prepare(plan).thenCompose(prepared->prepared.replayed()?CompletableFuture.completedFuture(prepared):coordinator.commitLedger(plan)).whenComplete((committed,failure)->{
            if(failure!=null){result.completeExceptionally(failure);return;}
            if(committed.replayed()){result.complete(committed);return;}
            Bukkit.getScheduler().runTask(plugin,()->applyInventory(player,plan,result));
        });
        return result;
    }

    private void applyInventory(Player player,CashPaymentPlan plan,CompletableFuture<CashTransactionResult> result){
        try{
            if(!player.isOnline())throw new IllegalStateException("Player disconnected after ledger commit");
            for(var line:plan.lines()){ItemStack item=player.getInventory().getItem(line.slot());CashSnapshot current=currency.snapshot(item);if(current==null||!line.cashUuid().equals(current.cashUuid())||line.pdcAmount()!=current.itemAmount())throw new IllegalStateException("Inventory slot changed before cash application: "+line.slot());}
            for(var line:plan.lines()){player.getInventory().setItem(line.slot(),line.splitAmount()>0?currency.materializePlannedCash(line.changeCashUuid(),line.splitAmount()):null);}
            coordinator.inventoryApplied(plan).whenComplete((ignored,failure)->{if(failure!=null)result.completeExceptionally(failure);else result.complete(new CashTransactionResult(plan.transactionUuid(),plan.idempotencyKey(),CashTransactionState.COMPLETED,plan.requestedAmount(),plan.returnedChange(),null,false));});
        }catch(RuntimeException failure){coordinator.recoveryRequired(plan,failure.getMessage()).whenComplete((ignored,journalFailure)->{if(journalFailure!=null)failure.addSuppressed(journalFailure);result.completeExceptionally(failure);});}
    }
}

package com.vaultsurvival.plugin.currency;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Pure planning plus durable idempotent preparation for bearer cash. */
public final class CashTransactionCoordinator {
    private final CashLedgerRepository ledger; private final CashRecoveryJournal journal;
    public CashTransactionCoordinator(CashLedgerRepository ledger, CashRecoveryJournal journal) { this.ledger=ledger;this.journal=journal; }

    public CompletableFuture<CashPaymentPlan> plan(CashInventorySnapshot snapshot,long amount,String idempotencyKey,String destinationType,String destinationId) {
        if(amount<=0) return CompletableFuture.failedFuture(new IllegalArgumentException("Payment amount must be positive"));
        List<CashInventorySnapshot.Entry> ordered=snapshot.entries().stream().sorted(Comparator.comparingInt(CashInventorySnapshot.Entry::slot)).toList();
        return ledger.load(ordered.stream().map(CashInventorySnapshot.Entry::cashUuid).toList()).thenApply(records->{
            List<CashPaymentPlan.Line> lines=new ArrayList<>(); long remaining=amount,total=0;
            for(var entry:ordered){ CashRecord record=records.get(entry.cashUuid());
                if(record==null) throw new IllegalStateException("Unknown cash UUID: "+entry.cashUuid());
                if(record.state()!=CashItemData.CashState.ACTIVE && record.state()!=CashItemData.CashState.DROPPED) throw new IllegalStateException("Cash is not active: "+entry.cashUuid());
                if(record.amount()!=entry.pdcAmount()) throw new IllegalStateException("Modified cash amount: "+entry.cashUuid());
                if(!"INVENTORY".equals(record.locationType()) || !snapshot.playerUuid().toString().equals(record.locationId())) throw new IllegalStateException("Stale physical cash location: "+entry.cashUuid());
                long consumed=Math.min(remaining,record.amount()); if(consumed<=0) break; total+=record.amount();remaining-=consumed;
                long split=record.amount()-consumed;
                lines.add(new CashPaymentPlan.Line(entry.slot(),entry.cashUuid(),entry.pdcAmount(),record.amount(),consumed,split,split>0?UUID.randomUUID():null,record.locationType(),record.locationId()));
            }
            if(remaining>0) throw new IllegalStateException("Not enough physical cash");
            long change=total-amount;
            if(change>0 && snapshot.emptySlots()<1 && lines.stream().noneMatch(l->l.splitAmount()>0)) throw new IllegalStateException("Inventory is full; change cannot be delivered safely");
            return new CashPaymentPlan(UUID.randomUUID(),idempotencyKey,snapshot.playerUuid(),lines,amount,change,destinationType,destinationId,List.of("CASH_PAYMENT"));
        });
    }

    public CompletableFuture<CashTransactionResult> prepare(CashPaymentPlan plan) { return journal.prepare(plan); }
    public CompletableFuture<CashTransactionResult> commitLedger(CashPaymentPlan plan){return ledger.commit(plan);}
    public CompletableFuture<Void> inventoryApplied(CashPaymentPlan plan){return ledger.completeInventory(plan);}
    public CompletableFuture<Void> recoveryRequired(CashPaymentPlan plan,String reason){return journal.transition(plan.transactionUuid(),CashTransactionState.LEDGER_COMMITTED,CashTransactionState.RECOVERY_REQUIRED,reason);}
}

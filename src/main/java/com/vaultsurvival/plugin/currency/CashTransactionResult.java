package com.vaultsurvival.plugin.currency;

import java.util.UUID;

public record CashTransactionResult(UUID transactionUuid, String idempotencyKey,
                                    CashTransactionState state, long paidAmount,
                                    long changeAmount, String failureReason,
                                    boolean replayed) {
    public boolean successful() { return state == CashTransactionState.COMPLETED || state == CashTransactionState.INVENTORY_APPLIED; }
}

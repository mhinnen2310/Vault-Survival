package com.vaultsurvival.plugin.currency;

/** Durable phases used to make physical inventory and ledger mutations recoverable. */
public enum CashTransactionState {
    PREPARED, LEDGER_COMMITTED, INVENTORY_APPLIED, COMPLETED, RECOVERY_REQUIRED, FAILED, REFUNDED
}

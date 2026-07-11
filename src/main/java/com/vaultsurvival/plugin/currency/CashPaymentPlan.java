package com.vaultsurvival.plugin.currency;

import java.util.List;
import java.util.UUID;

/** Exact, replayable description of a physical cash mutation. */
public record CashPaymentPlan(UUID transactionUuid, String idempotencyKey, UUID playerUuid,
                              List<Line> lines, long requestedAmount, long returnedChange,
                              String destinationType, String destinationId,
                              List<String> auditEvents) {
    public CashPaymentPlan {
        lines = List.copyOf(lines); auditEvents = List.copyOf(auditEvents);
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new IllegalArgumentException("idempotencyKey is required");
        if (requestedAmount <= 0) throw new IllegalArgumentException("requestedAmount must be positive");
    }
    public record Line(int slot, UUID cashUuid, long pdcAmount, long databaseAmount,
                       long consumedAmount, long splitAmount, UUID changeCashUuid, String validatedLocationType,
                       String validatedLocationId) { }
}

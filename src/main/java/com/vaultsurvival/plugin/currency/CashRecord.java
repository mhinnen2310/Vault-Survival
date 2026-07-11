package com.vaultsurvival.plugin.currency;

import java.util.UUID;

/** Complete immutable authoritative cash row returned by one validation query. */
public record CashRecord(UUID cashUuid,long amount,CashItemData.CashState state,String locationType,
                         String locationId,UUID ownerUuid,UUID createdBy) {
    public boolean matches(CashSnapshot snapshot) {
        return snapshot != null && cashUuid.equals(snapshot.cashUuid()) && amount == snapshot.itemAmount()
            && amount > 0 && (state == CashItemData.CashState.ACTIVE || state == CashItemData.CashState.DROPPED);
    }
}

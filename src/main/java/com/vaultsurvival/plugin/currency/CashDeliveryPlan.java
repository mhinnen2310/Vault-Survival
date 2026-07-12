package com.vaultsurvival.plugin.currency;

import java.util.List;
import java.util.UUID;

/** Inventory delivery output; LOCKER is used when the inventory cannot safely accept change. */
public record CashDeliveryPlan(UUID playerUuid, List<Delivery> deliveries, Policy policy) {
    public CashDeliveryPlan { deliveries = List.copyOf(deliveries); }
    public enum Policy { INVENTORY_OR_LOCKER, INVENTORY_ONLY, LOCKER }
    public record Delivery(UUID cashUuid, long amount, String locationType, String locationId) { }
}

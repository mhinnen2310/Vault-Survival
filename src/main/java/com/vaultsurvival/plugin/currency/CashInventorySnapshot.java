package com.vaultsurvival.plugin.currency;

import java.util.List;
import java.util.UUID;

/** Immutable main-thread capture. No Bukkit object is allowed past this boundary. */
public record CashInventorySnapshot(UUID playerUuid, List<Entry> entries, int emptySlots, long capturedAt) {
    public CashInventorySnapshot { entries = List.copyOf(entries); }
    public record Entry(int slot, UUID cashUuid, long pdcAmount) { }
}

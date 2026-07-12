package com.vaultsurvival.plugin.currency;

import java.util.UUID;

/** Immutable PDC snapshot captured from an ItemStack on the Paper thread. */
public record CashSnapshot(UUID cashUuid, long itemAmount) { }

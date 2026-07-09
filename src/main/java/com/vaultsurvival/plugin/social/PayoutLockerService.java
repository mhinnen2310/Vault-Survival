package com.vaultsurvival.plugin.social;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

public interface PayoutLockerService {
    int storePayout(UUID playerUuid, long amount, String sourceType, String sourceId, String details);
    List<ContractData.PayoutLockerEntry> getPending(UUID playerUuid);
    List<ContractData.PayoutLockerEntry> getAllPending();
    long getPendingTotal(UUID playerUuid);
    boolean claim(Player player);
}

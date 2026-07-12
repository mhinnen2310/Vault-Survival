package com.vaultsurvival.plugin.social;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

public interface PayoutLockerService {
    java.util.concurrent.CompletableFuture<Integer> storePayout(UUID playerUuid, long amount, String sourceType, String sourceId, String details);
    java.util.concurrent.CompletableFuture<Integer> storePayout(UUID playerUuid,long amount,String sourceType,String sourceId,String details,com.vaultsurvival.plugin.currency.CashBusinessMutation mutation);
    List<ContractData.PayoutLockerEntry> getPending(UUID playerUuid);
    List<ContractData.PayoutLockerEntry> getAllPending();
    long getPendingTotal(UUID playerUuid);
    boolean claim(Player player);
    /** Claims only merchant-shop proceeds; intended for an owned shop NPC interaction. */
    boolean claimMerchantShop(Player player);
}

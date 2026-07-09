package com.vaultsurvival.plugin.social;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

public interface EscrowService {
    boolean lockPlayerCash(Player payer, int contractId, long amount);
    boolean hasLockedEscrow(int contractId, long amount);
    long getLockedAmount(int contractId);
    boolean releaseToPayoutLocker(int contractId, UUID recipientUuid, String details);
    boolean refundToPayoutLocker(int contractId, UUID payerUuid, String details);
    boolean markDisputed(int contractId);
    List<ContractData.EscrowRecord> getEscrows(int contractId);
    List<ContractData.EscrowRecord> getAllEscrows();
}

package com.vaultsurvival.plugin.social;

import java.util.List;
import java.util.UUID;
import org.bukkit.entity.Player;

public interface ContractService {
    ContractData.Contract createContract(UUID issuer, UUID target, String description, long amount, long deadlineHours);
    ContractData.Contract createPaidContract(Player issuer, UUID target, String description, long amount, long deadlineHours,
                                             ContractData.ContractSource source);
    boolean acceptContract(int contractId, UUID acceptor);
    boolean completeContract(int contractId, UUID completer);
    boolean cancelContract(int contractId, UUID canceller);
    boolean disputeContract(int contractId, UUID actorUuid, String reason);
    List<ContractData.Contract> getContracts(UUID player);
    List<ContractData.Contract> getAllContracts();
    ContractData.Contract getContract(int id);
    void loadAll();
}

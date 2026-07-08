package com.vaultsurvival.plugin.social;

import java.util.List;
import java.util.UUID;

public interface ContractService {
    ContractData.Contract createContract(UUID issuer, UUID target, String description, long amount, long deadlineHours);
    boolean acceptContract(int contractId, UUID acceptor);
    boolean completeContract(int contractId, UUID completer);
    boolean cancelContract(int contractId, UUID canceller);
    List<ContractData.Contract> getContracts(UUID player);
    ContractData.Contract getContract(int id);
    void loadAll();
}

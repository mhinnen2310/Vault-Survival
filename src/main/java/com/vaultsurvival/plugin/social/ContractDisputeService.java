package com.vaultsurvival.plugin.social;

import java.util.List;
import java.util.UUID;

public interface ContractDisputeService {
    boolean openDispute(int contractId, UUID actorUuid, String reason);
    List<Integer> getOpenDisputeContractIds();
}

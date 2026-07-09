package com.vaultsurvival.plugin.social;

import java.util.List;
import java.util.UUID;

public interface ContractAuditService {
    void log(int contractId, UUID actorUuid, String action, long amount, String details);
    List<String> recent(int limit);
}

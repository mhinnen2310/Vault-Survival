package com.vaultsurvival.plugin.social;

import java.util.UUID;

public class ContractData {
    public enum ContractStatus { PENDING, ACCEPTED, COMPLETED, CANCELLED }

    public static class Contract {
        private final int id;
        private final UUID issuerUuid, targetUuid;
        private final String description;
        private final long amount;
        private final long deadline;
        private ContractStatus status;

        public Contract(int id, UUID issuer, UUID target, String desc, long amount, long deadline, ContractStatus status) {
            this.id = id; this.issuerUuid = issuer; this.targetUuid = target; this.description = desc;
            this.amount = amount; this.deadline = deadline; this.status = status;
        }
        public int getId() { return id; }
        public UUID getIssuerUuid() { return issuerUuid; }
        public UUID getTargetUuid() { return targetUuid; }
        public String getDescription() { return description; }
        public long getAmount() { return amount; }
        public long getDeadline() { return deadline; }
        public ContractStatus getStatus() { return status; }
        public void setStatus(ContractStatus s) { this.status = s; }
    }
}

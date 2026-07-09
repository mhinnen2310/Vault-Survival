package com.vaultsurvival.plugin.social;

import java.util.UUID;

public class ContractData {
    public enum ContractStatus { PENDING, ACCEPTED, COMPLETED, CANCELLED, DISPUTED }
    public enum ContractSource { DISTRICT_JOB, MERCHANT_ORDER, SPAWN_CITY_JOB, KINGDOM_SUPPORT, SYSTEM_EVENT, PLAYER_CONTRACT }
    public enum EscrowStatus { LOCKED, RELEASED, REFUNDED, DISPUTED }
    public enum PayoutStatus { PENDING, CLAIMED }
    public enum DisputeStatus { OPEN, RESOLVED, DISMISSED }

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

    public static class EscrowRecord {
        private final int id;
        private final int contractId;
        private final long amount;
        private final String status;
        private final String sourceType;

        public EscrowRecord(int id, int contractId, long amount, String status, String sourceType) {
            this.id = id;
            this.contractId = contractId;
            this.amount = amount;
            this.status = status;
            this.sourceType = sourceType;
        }
        public int getId() { return id; }
        public int getContractId() { return contractId; }
        public long getAmount() { return amount; }
        public String getStatus() { return status; }
        public String getSourceType() { return sourceType; }
    }

    public static class PayoutLockerEntry {
        private final int id;
        private final UUID playerUuid;
        private final long amount;
        private final String sourceType;
        private final String sourceId;
        private final String details;
        private final PayoutStatus status;

        public PayoutLockerEntry(int id, UUID playerUuid, long amount, String sourceType,
                                 String sourceId, String details, PayoutStatus status) {
            this.id = id;
            this.playerUuid = playerUuid;
            this.amount = amount;
            this.sourceType = sourceType;
            this.sourceId = sourceId;
            this.details = details;
            this.status = status;
        }
        public int getId() { return id; }
        public UUID getPlayerUuid() { return playerUuid; }
        public long getAmount() { return amount; }
        public String getSourceType() { return sourceType; }
        public String getSourceId() { return sourceId; }
        public String getDetails() { return details; }
        public PayoutStatus getStatus() { return status; }
    }
}

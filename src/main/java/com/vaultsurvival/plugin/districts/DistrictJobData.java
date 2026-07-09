package com.vaultsurvival.plugin.districts;

import java.util.UUID;

public class DistrictJobData {
    public enum JobType {
        DELIVERY,
        GATHERING,
        TRANSPORT_PACKAGE,
        TALK_TO_NPC,
        EXPLORATION_CHECKPOINT,
        BUILDING,
        GUARD_DUTY,
        BOUNTY,
        REPAIR_MATERIALS,
        MARKET_SUPPLY,
        WAR_CONTRACT,
        TRANSPORT_ESCORT,
        CUSTOM
    }

    public enum JobStatus {
        DRAFT,
        ACTIVE,
        CLAIMED,
        IN_PROGRESS,
        READY_TO_TURN_IN,
        SUBMITTED,
        APPROVED,
        DENIED,
        COMPLETED,
        PAID,
        PAYOUT_PENDING,
        EXPIRED,
        CANCELLED,
        DISPUTED
    }

    public enum TrackingMode {
        AUTO_ITEM,
        AUTO_NPC,
        AUTO_CHECKPOINT,
        MANUAL
    }

    public static class Job {
        private final int id;
        private final int districtId;
        private final UUID creatorUuid;
        private final JobType type;
        private final String title;
        private final String description;
        private final long reward;
        private final long deadline;
        private final String requiredItem;
        private final int requiredAmount;
        private final String origin;
        private final String destination;
        private final String checkpoint;
        private final TrackingMode trackingMode;
        private final boolean manualApproval;
        private JobStatus status;

        public Job(int id, int districtId, UUID creatorUuid, JobType type, String title, String description,
                   long reward, long deadline, String requiredItem, int requiredAmount, String origin,
                   String destination, String checkpoint, TrackingMode trackingMode, boolean manualApproval,
                   JobStatus status) {
            this.id = id;
            this.districtId = districtId;
            this.creatorUuid = creatorUuid;
            this.type = type;
            this.title = title;
            this.description = description;
            this.reward = reward;
            this.deadline = deadline;
            this.requiredItem = requiredItem;
            this.requiredAmount = requiredAmount;
            this.origin = origin;
            this.destination = destination;
            this.checkpoint = checkpoint;
            this.trackingMode = trackingMode;
            this.manualApproval = manualApproval;
            this.status = status;
        }

        public int getId() { return id; }
        public int getDistrictId() { return districtId; }
        public UUID getCreatorUuid() { return creatorUuid; }
        public JobType getType() { return type; }
        public String getTitle() { return title; }
        public String getDescription() { return description; }
        public long getReward() { return reward; }
        public long getDeadline() { return deadline; }
        public String getRequiredItem() { return requiredItem; }
        public int getRequiredAmount() { return requiredAmount; }
        public String getOrigin() { return origin; }
        public String getDestination() { return destination; }
        public String getCheckpoint() { return checkpoint; }
        public TrackingMode getTrackingMode() { return trackingMode; }
        public boolean isManualApproval() { return manualApproval; }
        public JobStatus getStatus() { return status; }
        public void setStatus(JobStatus status) { this.status = status; }
    }

    public static class Claim {
        private final int id;
        private final int jobId;
        private final UUID playerUuid;
        private JobStatus status;

        public Claim(int id, int jobId, UUID playerUuid, JobStatus status) {
            this.id = id;
            this.jobId = jobId;
            this.playerUuid = playerUuid;
            this.status = status;
        }

        public int getId() { return id; }
        public int getJobId() { return jobId; }
        public UUID getPlayerUuid() { return playerUuid; }
        public JobStatus getStatus() { return status; }
        public void setStatus(JobStatus status) { this.status = status; }
    }
}

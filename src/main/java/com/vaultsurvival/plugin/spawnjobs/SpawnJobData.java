package com.vaultsurvival.plugin.spawnjobs;

import java.util.UUID;

public class SpawnJobData {
    public enum JobType {
        ITEM_DELIVERY,
        TRANSPORT_PACKAGE,
        TALK_TO_NPC,
        EXPLORATION_CHECKPOINT,
        GUARD_PATROL,
        BOUNTY,
        CUSTOM
    }

    public enum PlayerJobStatus {
        ACTIVE,
        COMPLETED,
        PAID,
        FAILED,
        ABANDONED
    }

    public enum PackageStatus {
        ACTIVE,
        TURNED_IN,
        LOST,
        EXPIRED,
        ABANDONED
    }

    public static class Job {
        private final int id;
        private final JobType type;
        private final String title;
        private final String description;
        private final long reward;
        private final String requiredItem;
        private final int requiredAmount;
        private final String destination;
        private final boolean enabled;

        public Job(int id, JobType type, String title, String description, long reward,
                   String requiredItem, int requiredAmount, String destination, boolean enabled) {
            this.id = id;
            this.type = type;
            this.title = title;
            this.description = description;
            this.reward = reward;
            this.requiredItem = requiredItem;
            this.requiredAmount = requiredAmount;
            this.destination = destination;
            this.enabled = enabled;
        }

        public int getId() { return id; }
        public JobType getType() { return type; }
        public String getTitle() { return title; }
        public String getDescription() { return description; }
        public long getReward() { return reward; }
        public String getRequiredItem() { return requiredItem; }
        public int getRequiredAmount() { return requiredAmount; }
        public String getDestination() { return destination; }
        public boolean isEnabled() { return enabled; }
    }

    public static class PlayerJob {
        private final int id;
        private final int jobId;
        private final UUID playerUuid;
        private PlayerJobStatus status;

        public PlayerJob(int id, int jobId, UUID playerUuid, PlayerJobStatus status) {
            this.id = id;
            this.jobId = jobId;
            this.playerUuid = playerUuid;
            this.status = status;
        }

        public int getId() { return id; }
        public int getJobId() { return jobId; }
        public UUID getPlayerUuid() { return playerUuid; }
        public PlayerJobStatus getStatus() { return status; }
        public void setStatus(PlayerJobStatus status) { this.status = status; }
    }
}

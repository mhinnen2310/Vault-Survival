package com.vaultsurvival.plugin.breach;

import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Data models for the Breach system.
 *
 * A breach is a multi-stage minigame a thief plays to steal cash from a vault.
 * The vault always protects at least 50% of its balance; the thief's performance
 * determines what percentage of the stealable half they actually get.
 */
public class BreachData {

    /**
     * The live state of an active breach minigame.
     * Stored in memory only (ConcurrentHashMap in BreachServiceImpl).
     * On crash, the DB record is marked FAILED_CRASH.
     */
    public static class ActiveBreach {
        private final UUID breachId;
        private final UUID thiefUuid;
        private final UUID vaultUuid;
        private final long vaultBalanceBefore;
        private final long stealableAmount;
        private Stage currentStage;
        private int stageIndex; // 0=Tumbler, 1=Pressure, 2=Dial
        private double score; // 0.0 - 1.0, accumulated across stages

        // ---- Timing Tumbler state ----
        private int tumblerTargetSlot; // 0-4, which slot is the target
        private int tumblerCurrentSlot; // 0-4, where the indicator currently is
        private int tumblerTick; // current tick in cycle
        private int tumblerAttempts; // remaining attempts
        private double tumblerScore;

        // ---- Pressure Balance state ----
        private float pressureLevel; // 0.0 - 1.0, current pressure
        private boolean pressureMovingUp; // direction of pressure change
        private int pressureTicksRemaining; // how long to hold balance
        private double pressureScore;

        // ---- Final Dial state ----
        private int[] dialTarget = new int[3]; // correct 3-digit combo (each 0-9)
        private int[] dialCurrent = new int[]{5, 5, 5}; // starting position
        private int dialAttemptsRemaining; // attempts left
        private int dialTicksRemaining; // time left
        private double dialScore;

        public ActiveBreach(UUID breachId, UUID thiefUuid, UUID vaultUuid,
                            long vaultBalanceBefore, long stealableAmount) {
            this.breachId = breachId;
            this.thiefUuid = thiefUuid;
            this.vaultUuid = vaultUuid;
            this.vaultBalanceBefore = vaultBalanceBefore;
            this.stealableAmount = stealableAmount;
            this.currentStage = Stage.TUMBLER;
            this.stageIndex = 0;
            this.score = 0.0;
        }

        /** Advance to the next stage, or mark as complete. */
        public void advanceStage() {
            stageIndex++;
            if (stageIndex >= 3) {
                currentStage = Stage.COMPLETE;
            } else {
                currentStage = Stage.values()[stageIndex];
            }
        }

        /** Calculate the total stolen amount based on the final score. */
        public long calculateStolenAmount() {
            // Score 0.0 = 0%, Score 1.0 = 50% of total balance (capped at stealable)
            double stealPercent = score * 0.50;
            return Math.max(0, (long) Math.floor(vaultBalanceBefore * stealPercent));
        }

        // ---- Getters / Setters ----

        public UUID getBreachId() { return breachId; }
        public UUID getThiefUuid() { return thiefUuid; }
        public UUID getVaultUuid() { return vaultUuid; }
        public long getVaultBalanceBefore() { return vaultBalanceBefore; }
        public long getStealableAmount() { return stealableAmount; }
        public Stage getCurrentStage() { return currentStage; }
        public int getStageIndex() { return stageIndex; }
        public double getScore() { return score; }
        public void setScore(double score) { this.score = Math.max(0, Math.min(1.0, score)); }

        // Tumbler
        public int getTumblerTargetSlot() { return tumblerTargetSlot; }
        public void setTumblerTargetSlot(int slot) { this.tumblerTargetSlot = slot; }
        public int getTumblerCurrentSlot() { return tumblerCurrentSlot; }
        public void setTumblerCurrentSlot(int slot) { this.tumblerCurrentSlot = slot; }
        public int getTumblerTick() { return tumblerTick; }
        public void setTumblerTick(int tick) { this.tumblerTick = tick; }
        public int getTumblerAttempts() { return tumblerAttempts; }
        public void setTumblerAttempts(int attempts) { this.tumblerAttempts = attempts; }
        public double getTumblerScore() { return tumblerScore; }
        public void setTumblerScore(double s) { this.tumblerScore = s; }

        // Pressure
        public float getPressureLevel() { return pressureLevel; }
        public void setPressureLevel(float level) { this.pressureLevel = Math.max(0, Math.min(1.0f, level)); }
        public boolean isPressureMovingUp() { return pressureMovingUp; }
        public void setPressureMovingUp(boolean up) { this.pressureMovingUp = up; }
        public int getPressureTicksRemaining() { return pressureTicksRemaining; }
        public void setPressureTicksRemaining(int ticks) { this.pressureTicksRemaining = ticks; }
        public double getPressureScore() { return pressureScore; }
        public void setPressureScore(double s) { this.pressureScore = s; }

        // Dial
        public int[] getDialTarget() { return dialTarget; }
        public void setDialTarget(int[] target) { this.dialTarget = target; }
        public int[] getDialCurrent() { return dialCurrent; }
        public void setDialCurrent(int[] current) { this.dialCurrent = current; }
        public int getDialAttemptsRemaining() { return dialAttemptsRemaining; }
        public void setDialAttemptsRemaining(int attempts) { this.dialAttemptsRemaining = attempts; }
        public int getDialTicksRemaining() { return dialTicksRemaining; }
        public void setDialTicksRemaining(int ticks) { this.dialTicksRemaining = ticks; }
        public double getDialScore() { return dialScore; }
        public void setDialScore(double s) { this.dialScore = s; }

        /** The three stages of the breach minigame. */
        public enum Stage {
            TUMBLER,    // Timing tumbler - click at the right moment
            PRESSURE,   // Pressure balance - keep a gauge balanced
            DIAL,       // Final dial - crack the combination
            COMPLETE    // Minigame finished (success or failure)
        }
    }

    /**
     * Simple record for breach log display.
     */
    public static class BreachLogEntry {
        private final UUID vaultUuid;
        private final UUID thiefUuid;
        private final String startedAt;
        private final String completedAt;
        private final boolean success;
        private final long stolenAmount;
        private final long vaultBalanceBefore;
        private final long vaultBalanceAfter;
        private final double breachScore;
        private final String failedReason;

        public BreachLogEntry(UUID vaultUuid, UUID thiefUuid, String startedAt, String completedAt,
                              boolean success, long stolenAmount, long vaultBalanceBefore,
                              long vaultBalanceAfter, double breachScore, String failedReason) {
            this.vaultUuid = vaultUuid;
            this.thiefUuid = thiefUuid;
            this.startedAt = startedAt;
            this.completedAt = completedAt;
            this.success = success;
            this.stolenAmount = stolenAmount;
            this.vaultBalanceBefore = vaultBalanceBefore;
            this.vaultBalanceAfter = vaultBalanceAfter;
            this.breachScore = breachScore;
            this.failedReason = failedReason;
        }

        public UUID getVaultUuid() { return vaultUuid; }
        public UUID getThiefUuid() { return thiefUuid; }
        public String getStartedAt() { return startedAt; }
        public String getCompletedAt() { return completedAt; }
        public boolean isSuccess() { return success; }
        public long getStolenAmount() { return stolenAmount; }
        public long getVaultBalanceBefore() { return vaultBalanceBefore; }
        public long getVaultBalanceAfter() { return vaultBalanceAfter; }
        public double getBreachScore() { return breachScore; }
        public String getFailedReason() { return failedReason; }
    }
}

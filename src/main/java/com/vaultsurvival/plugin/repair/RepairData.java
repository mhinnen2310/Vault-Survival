package com.vaultsurvival.plugin.repair;

/**
 * Data models for the Repairmen system.
 *
 * Each district has repair points that are consumed when temporary damage
 * is recorded. When points are exhausted, restores slow down significantly.
 * Points reset daily and repairmen must be paid from the district treasury.
 */
public class RepairData {

    /**
     * Tracks the repair state for a single district.
     */
    public static class DistrictRepairState {
        private final int districtId;
        private int repairPoints;
        private long lastWagePaid;    // epoch millis of last successful wage payment
        private long lastPointsReset; // epoch millis of last daily reset
        private boolean isExhausted;  // points depleted before next reset

        public DistrictRepairState(int districtId, int repairPoints, long lastWagePaid,
                                   long lastPointsReset, boolean isExhausted) {
            this.districtId = districtId;
            this.repairPoints = repairPoints;
            this.lastWagePaid = lastWagePaid;
            this.lastPointsReset = lastPointsReset;
            this.isExhausted = isExhausted;
        }

        /** Check if a new day has started (24 hours since last reset). */
        public boolean isNewDay() {
            return System.currentTimeMillis() - lastPointsReset >= 24 * 60 * 60 * 1000L;
        }

        /** Consume one repair point. Returns false if already exhausted. */
        public boolean consumePoint() {
            if (repairPoints <= 0) {
                isExhausted = true;
                return false;
            }
            repairPoints--;
            if (repairPoints <= 0) isExhausted = true;
            return true;
        }

        /** Reset repair points for a new day. */
        public void resetPoints(int dailyPoints) {
            this.repairPoints = dailyPoints;
            this.isExhausted = false;
            this.lastPointsReset = System.currentTimeMillis();
        }

        /** Mark wage as paid. */
        public void markWagePaid() {
            this.lastWagePaid = System.currentTimeMillis();
        }

        // Getters/Setters
        public int getDistrictId() { return districtId; }
        public int getRepairPoints() { return repairPoints; }
        public void setRepairPoints(int points) { this.repairPoints = points; }
        public long getLastWagePaid() { return lastWagePaid; }
        public long getLastPointsReset() { return lastPointsReset; }
        public boolean isExhausted() { return isExhausted; }
        public void setExhausted(boolean exhausted) { this.isExhausted = exhausted; }
    }
}

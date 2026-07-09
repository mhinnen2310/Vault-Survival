package com.vaultsurvival.plugin.rail;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Data models for the train journey instance system.
 *
 * Journeys track the full lifecycle from ticket purchase through boarding,
 * transit countdown, and arrival at the destination station.
 */
public class RailJourneyData {

    /**
     * Journey states in the lifecycle:
     * TICKETED  - Player bought a ticket, waiting for boarding to open.
     * BOARDING  - Boarding window is open, player can /station board.
     * BOARDED   - Player has boarded the train.
     * DEPARTED  - Train has departed, countdown started.
     * IN_TRANSIT - Mid-journey, player is in the train car.
     * ARRIVED   - Player has arrived at destination.
     * CANCELLED - Journey was cancelled (by staff or player logout).
     * FAILED    - Journey failed (e.g., destination invalid, world unloaded).
     */
    public enum JourneyState {
        TICKETED,
        BOARDING,
        BOARDED,
        DEPARTED,
        IN_TRANSIT,
        ARRIVED,
        CANCELLED,
        FAILED
    }

    /**
     * Represents a single player's train journey.
     * Stored in memory (ConcurrentHashMap) and partially persisted
     * via the journey_logs table for logout/rejoin recovery.
     */
    public static class Journey {
        private final UUID playerUuid;
        private final String playerName;
        private final int routeId;
        private final int fromStationId;
        private final int toStationId;
        private final String fromStationName;
        private final String toStationName;
        private final long ticketPrice;
        private final int totalTravelTicks;
        private JourneyState state;
        private int ticksRemaining;
        private Location trainCarLocation;
        private Location previousLocation;
        private final long createdAt;
        private long stateChangedAt;

        // Task IDs for the scheduler
        private int boardingTaskId = -1;
        private int journeyTaskId = -1;

        public Journey(UUID playerUuid, String playerName, int routeId,
                       int fromStationId, int toStationId,
                       String fromStationName, String toStationName,
                       long ticketPrice, int totalTravelTicks) {
            this.playerUuid = playerUuid;
            this.playerName = playerName;
            this.routeId = routeId;
            this.fromStationId = fromStationId;
            this.toStationId = toStationId;
            this.fromStationName = fromStationName;
            this.toStationName = toStationName;
            this.ticketPrice = ticketPrice;
            this.totalTravelTicks = totalTravelTicks;
            this.state = JourneyState.TICKETED;
            this.ticksRemaining = totalTravelTicks;
            this.createdAt = System.currentTimeMillis();
            this.stateChangedAt = System.currentTimeMillis();
        }

        // Getters
        public UUID getPlayerUuid() { return playerUuid; }
        public String getPlayerName() { return playerName; }
        public int getRouteId() { return routeId; }
        public int getFromStationId() { return fromStationId; }
        public int getToStationId() { return toStationId; }
        public String getFromStationName() { return fromStationName; }
        public String getToStationName() { return toStationName; }
        public long getTicketPrice() { return ticketPrice; }
        public int getTotalTravelTicks() { return totalTravelTicks; }
        public JourneyState getState() { return state; }
        public int getTicksRemaining() { return ticksRemaining; }
        public Location getTrainCarLocation() { return trainCarLocation; }
        public Location getPreviousLocation() { return previousLocation; }
        public long getCreatedAt() { return createdAt; }
        public long getStateChangedAt() { return stateChangedAt; }
        public int getBoardingTaskId() { return boardingTaskId; }
        public int getJourneyTaskId() { return journeyTaskId; }

        // Setters
        public void setState(JourneyState state) {
            this.state = state;
            this.stateChangedAt = System.currentTimeMillis();
        }
        public void setTicksRemaining(int ticks) { this.ticksRemaining = ticks; }
        public void setTrainCarLocation(Location loc) { this.trainCarLocation = loc != null ? loc.clone() : null; }
        public void setPreviousLocation(Location loc) { this.previousLocation = loc != null ? loc.clone() : null; }
        public void setBoardingTaskId(int id) { this.boardingTaskId = id; }
        public void setJourneyTaskId(int id) { this.journeyTaskId = id; }

        /**
         * Whether the journey is in an active state (player is on the train).
         */
        public boolean isActive() {
            return state == JourneyState.BOARDED ||
                   state == JourneyState.DEPARTED ||
                   state == JourneyState.IN_TRANSIT;
        }

        /**
         * Whether the journey has ended.
         */
        public boolean isEnded() {
            return state == JourneyState.ARRIVED ||
                   state == JourneyState.CANCELLED ||
                   state == JourneyState.FAILED;
        }

        /**
         * Whether the player can board.
         */
        public boolean canBoard() {
            return state == JourneyState.TICKETED || state == JourneyState.BOARDING;
        }

        /**
         * Format ticks remaining as a human-readable string (mm:ss).
         */
        public String getTimeRemaining() {
            int seconds = ticksRemaining / 20;
            int minutes = seconds / 60;
            int secs = seconds % 60;
            return String.format("%d:%02d", minutes, secs);
        }
    }
}

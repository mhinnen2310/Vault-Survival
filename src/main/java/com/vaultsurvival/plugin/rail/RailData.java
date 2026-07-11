package com.vaultsurvival.plugin.rail;

import java.util.UUID;

public class RailData {

    public enum StationStatus {
        PENDING,
        ACTIVE,
        SUSPENDED,
        DENIED
    }

    public enum RouteStatus {
        PENDING,
        ACTIVE,
        SUSPENDED
    }

    public static class Station {
        private final int id;
        private final int districtId;
        private final UUID requesterUuid;
        private final String name;
        private final String worldName;
        private final int platMinX, platMinY, platMinZ;
        private final int platMaxX, platMaxY, platMaxZ;
        private final double arrX, arrY, arrZ;
        private final float arrYaw, arrPitch;
        private long ticketPrice;
        private final long upkeepCost;
        private final int kingdomTaxPercent;
        private StationStatus status;
        private long totalRevenue;
        private final long createdAt;

        public Station(int id, int districtId, UUID requesterUuid, String name, String worldName,
                       int platMinX, int platMinY, int platMinZ, int platMaxX, int platMaxY, int platMaxZ,
                       double arrX, double arrY, double arrZ, float arrYaw, float arrPitch,
                       long ticketPrice, long upkeepCost, int kingdomTaxPercent,
                       StationStatus status, long totalRevenue, long createdAt) {
            this.id = id;
            this.districtId = districtId;
            this.requesterUuid = requesterUuid;
            this.name = name;
            this.worldName = worldName;
            this.platMinX = platMinX; this.platMinY = platMinY; this.platMinZ = platMinZ;
            this.platMaxX = platMaxX; this.platMaxY = platMaxY; this.platMaxZ = platMaxZ;
            this.arrX = arrX; this.arrY = arrY; this.arrZ = arrZ;
            this.arrYaw = arrYaw; this.arrPitch = arrPitch;
            this.ticketPrice = ticketPrice;
            this.upkeepCost = upkeepCost;
            this.kingdomTaxPercent = kingdomTaxPercent;
            this.status = status;
            this.totalRevenue = totalRevenue;
            this.createdAt = createdAt;
        }

        public int getId() { return id; }
        public int getDistrictId() { return districtId; }
        public UUID getRequesterUuid() { return requesterUuid; }
        public String getName() { return name; }
        public String getWorldName() { return worldName; }
        public int getPlatMinX() { return platMinX; }
        public int getPlatMinY() { return platMinY; }
        public int getPlatMinZ() { return platMinZ; }
        public int getPlatMaxX() { return platMaxX; }
        public int getPlatMaxY() { return platMaxY; }
        public int getPlatMaxZ() { return platMaxZ; }
        public double getArrX() { return arrX; }
        public double getArrY() { return arrY; }
        public double getArrZ() { return arrZ; }
        public float getArrYaw() { return arrYaw; }
        public float getArrPitch() { return arrPitch; }
        public long getTicketPrice() { return ticketPrice; }
        public void setTicketPrice(long ticketPrice) { this.ticketPrice = ticketPrice; }
        public long getUpkeepCost() { return upkeepCost; }
        public int getKingdomTaxPercent() { return kingdomTaxPercent; }
        public StationStatus getStatus() { return status; }
        public void setStatus(StationStatus status) { this.status = status; }
        public long getTotalRevenue() { return totalRevenue; }
        public void setTotalRevenue(long totalRevenue) { this.totalRevenue = totalRevenue; }
        public long getCreatedAt() { return createdAt; }

        public boolean isInsidePlatform(int x, int y, int z) {
            return x >= platMinX && x <= platMaxX &&
                   y >= platMinY && y <= platMaxY &&
                   z >= platMinZ && z <= platMaxZ;
        }

        public org.bukkit.Location getArrivalLocation() {
            var world = org.bukkit.Bukkit.getWorld(worldName);
            if (world == null) return null;
            return new org.bukkit.Location(world, arrX, arrY, arrZ, arrYaw, arrPitch);
        }
    }

    public static class Route {
        private final int id;
        private final int fromStationId;
        private final int toStationId;
        private final long ticketPrice;
        private final int kingdomTaxPercent;
        private final int travelTimeTicks;
        private RouteStatus status;
        private final long createdAt;

        public Route(int id, int fromStationId, int toStationId, long ticketPrice,
                     int kingdomTaxPercent, int travelTimeTicks, RouteStatus status, long createdAt) {
            this.id = id;
            this.fromStationId = fromStationId;
            this.toStationId = toStationId;
            this.ticketPrice = ticketPrice;
            this.kingdomTaxPercent = kingdomTaxPercent;
            this.travelTimeTicks = travelTimeTicks;
            this.status = status;
            this.createdAt = createdAt;
        }

        public int getId() { return id; }
        public int getFromStationId() { return fromStationId; }
        public int getToStationId() { return toStationId; }
        public long getTicketPrice() { return ticketPrice; }
        public int getKingdomTaxPercent() { return kingdomTaxPercent; }
        public int getTravelTimeTicks() { return travelTimeTicks; }
        public RouteStatus getStatus() { return status; }
        public void setStatus(RouteStatus status) { this.status = status; }
        public long getCreatedAt() { return createdAt; }
    }
}

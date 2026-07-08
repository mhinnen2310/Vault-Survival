package com.vaultsurvival.plugin.social;

import org.bukkit.Location;
import org.bukkit.Material;

import java.util.UUID;

public class StationData {
    public enum RouteType { FREE, PAID }

    public static class Station {
        private final int id;
        private final String name;
        private final String worldName;
        private final int x, y, z;
        private final RouteType type;
        private final long cost;
        private final UUID ownerUuid;

        public Station(int id, String name, String worldName, int x, int y, int z, RouteType type, long cost, UUID ownerUuid) {
            this.id = id; this.name = name; this.worldName = worldName; this.x = x; this.y = y; this.z = z;
            this.type = type; this.cost = cost; this.ownerUuid = ownerUuid;
        }
        public int getId() { return id; }
        public String getName() { return name; }
        public String getWorldName() { return worldName; }
        public int getX() { return x; } public int getY() { return y; } public int getZ() { return z; }
        public RouteType getType() { return type; }
        public long getCost() { return cost; }
        public UUID getOwnerUuid() { return ownerUuid; }
        public Location getLocation() {
            var w = org.bukkit.Bukkit.getWorld(worldName);
            return w != null ? new Location(w, x + 0.5, y, z + 0.5) : null;
        }
    }
}

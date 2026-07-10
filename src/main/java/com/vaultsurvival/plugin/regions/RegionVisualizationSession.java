package com.vaultsurvival.plugin.regions;

import org.bukkit.World;

import java.util.Locale;
import java.util.UUID;

/** Per-player, cached state for one cuboid visualization. */
public final class RegionVisualizationSession {

    public enum Mode {
        TEN_SECONDS(10), THIRTY_SECONDS(30), SIXTY_SECONDS(60),
        UNTIL_CANCELLED(-1), WHILE_EDITING(-1);

        private final int seconds;
        Mode(int seconds) { this.seconds = seconds; }
        public int seconds() { return seconds; }

        public static Mode parse(String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "10" -> TEN_SECONDS;
                case "30" -> THIRTY_SECONDS;
                case "60" -> SIXTY_SECONDS;
                case "persistent", "until_cancelled", "until-cancelled" -> UNTIL_CANCELLED;
                case "editing", "while_editing", "while-editing" -> WHILE_EDITING;
                default -> null;
            };
        }
    }

    public record Bounds(World world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public Bounds {
            if (world == null) throw new IllegalArgumentException("world is required");
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                throw new IllegalArgumentException("bounds must be normalized");
            }
        }

        public int width() { return maxX - minX + 1; }
        public int height() { return maxY - minY + 1; }
        public int depth() { return maxZ - minZ + 1; }
        public long volume() { return (long) width() * height() * depth(); }
        public double outerMaxX() { return maxX + 1.0; }
        public double outerMaxY() { return maxY + 1.0; }
        public double outerMaxZ() { return maxZ + 1.0; }
    }

    private final UUID viewerId;
    private Bounds bounds;
    private RegionData.RegionType type;
    private String displayName;
    private Mode mode;
    private long expiresAtMillis;
    private boolean sideGrid;
    private boolean floorGrid;
    private boolean sharedNearby;
    private RegionParticleRenderer.RenderPlan renderPlan;

    public RegionVisualizationSession(UUID viewerId, Bounds bounds, RegionData.RegionType type,
                                      String displayName, Mode mode, boolean sideGrid, boolean floorGrid,
                                      boolean sharedNearby) {
        this.viewerId = viewerId;
        update(bounds, type, displayName, mode, sideGrid, floorGrid, sharedNearby);
    }

    public void update(Bounds bounds, RegionData.RegionType type, String displayName, Mode mode,
                       boolean sideGrid, boolean floorGrid, boolean sharedNearby) {
        this.bounds = bounds;
        this.type = type;
        this.displayName = displayName;
        this.mode = mode;
        this.sideGrid = sideGrid;
        this.floorGrid = floorGrid;
        this.sharedNearby = sharedNearby;
        this.expiresAtMillis = mode.seconds() < 0 ? Long.MAX_VALUE
            : System.currentTimeMillis() + mode.seconds() * 1000L;
        this.renderPlan = null;
    }

    public UUID viewerId() { return viewerId; }
    public Bounds bounds() { return bounds; }
    public RegionData.RegionType type() { return type; }
    public String displayName() { return displayName; }
    public Mode mode() { return mode; }
    public long expiresAtMillis() { return expiresAtMillis; }
    public boolean sideGrid() { return sideGrid; }
    public boolean floorGrid() { return floorGrid; }
    public boolean sharedNearby() { return sharedNearby; }
    public RegionParticleRenderer.RenderPlan renderPlan() { return renderPlan; }
    public void renderPlan(RegionParticleRenderer.RenderPlan renderPlan) { this.renderPlan = renderPlan; }
    public boolean expired() { return System.currentTimeMillis() >= expiresAtMillis; }
    public void setMode(Mode mode) {
        this.mode = mode;
        this.expiresAtMillis = mode.seconds() < 0 ? Long.MAX_VALUE
            : System.currentTimeMillis() + mode.seconds() * 1000L;
    }
    public void setSideGrid(boolean enabled) { sideGrid = enabled; renderPlan = null; }
    public void setFloorGrid(boolean enabled) { floorGrid = enabled; renderPlan = null; }
}

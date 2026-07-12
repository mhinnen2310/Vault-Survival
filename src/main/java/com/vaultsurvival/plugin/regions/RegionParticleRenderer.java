package com.vaultsurvival.plugin.regions;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;

/** Builds bounded, reusable particle point batches. It never loads chunks or touches the world. */
public final class RegionParticleRenderer {
    public record Point(double x, double y, double z, Layer layer) {}
    public enum Layer { PERIMETER, PILLAR, SIDE_GRID, FLOOR_GRID, POS1, POS2 }
    public record RenderPlan(List<Point> points, double effectiveSpacing, int requestedPoints,
                             int perimeterPoints, int pillarPoints, int sideGridPoints,
                             int floorGridPoints, int markerPoints) {}

    private final double perimeterSpacing;
    private final double verticalSpacing;
    private final double cornerDensityMultiplier;
    private final int maxParticles;
    private final boolean topPerimeterEnabled;
    private final boolean defaultSideGridEnabled;
    private final boolean defaultFloorGridEnabled;
    private final double sideGridSpacing;
    private final double floorGridSpacing;
    private final boolean dynamicSpacing;
    private final long mediumVolume;
    private final long largeVolume;
    private final double mediumMultiplier;
    private final double largeMultiplier;

    public RegionParticleRenderer(FileConfiguration config) {
        String p = "regions.visualization.";
        perimeterSpacing = Math.max(0.25, config.getDouble(p + "particles.perimeterSpacing", 0.5));
        verticalSpacing = Math.max(0.25, config.getDouble(p + "particles.verticalSpacing", 0.5));
        cornerDensityMultiplier = Math.max(1, config.getDouble(p + "particles.cornerDensityMultiplier", 2.0));
        maxParticles = Math.max(100, config.getInt(p + "particles.maxParticlesPerTickPerPlayer", 2500));
        topPerimeterEnabled = config.getBoolean(p + "grid.topPerimeterEnabled", true);
        defaultSideGridEnabled = config.getBoolean(p + "grid.sideGridEnabled", true);
        defaultFloorGridEnabled = config.getBoolean(p + "grid.floorGridEnabled", true);
        sideGridSpacing = Math.max(1, config.getDouble(p + "grid.sideGridSpacing", 4));
        floorGridSpacing = Math.max(1, config.getDouble(p + "grid.floorGridSpacing", 4));
        dynamicSpacing = config.getBoolean(p + "scaling.dynamicSpacingEnabled", true);
        mediumVolume = config.getLong(p + "scaling.largeRegionVolumeThreshold", 100000L);
        largeVolume = config.getLong(p + "scaling.veryLargeRegionVolumeThreshold", 1000000L);
        mediumMultiplier = Math.max(1, config.getDouble(p + "scaling.largeRegionSpacingMultiplier", 2));
        largeMultiplier = Math.max(1, config.getDouble(p + "scaling.veryLargeRegionSpacingMultiplier", 4));
    }

    public boolean defaultSideGridEnabled() { return defaultSideGridEnabled; }
    public boolean defaultFloorGridEnabled() { return defaultFloorGridEnabled; }
    public int maxParticles() { return maxParticles; }

    public RenderPlan build(RegionVisualizationSession.Bounds b, boolean sideGrid, boolean floorGrid) {
        return build(b, sideGrid, floorGrid, RegionVisualizationSession.Density.NORMAL);
    }

    public RenderPlan build(RegionVisualizationSession.Bounds b, boolean sideGrid, boolean floorGrid,
                            RegionVisualizationSession.Density density) {
        List<Point> all = new ArrayList<>();
        double adaptive = dynamicSpacing ? (b.volume() >= largeVolume ? largeMultiplier
            : b.volume() >= mediumVolume ? mediumMultiplier : 1.0) : 1.0;
        double densityMultiplier = switch (density) { case NORMAL -> 1.0; case DENSE -> 0.75; case EXTREME -> 0.5; };
        double multiplier = adaptive * densityMultiplier;
        double perimeter = perimeterSpacing * multiplier;
        double vertical = verticalSpacing * multiplier;
        double x1 = b.minX(), y1 = b.minY(), z1 = b.minZ();
        double x2 = b.outerMaxX(), y2 = b.outerMaxY(), z2 = b.outerMaxZ();

        rectangle(all, x1, y1, z1, x2, z2, perimeter, Layer.PERIMETER);
        if (topPerimeterEnabled) rectangle(all, x1, y2, z1, x2, z2, perimeter, Layer.PERIMETER);
        for (double x : new double[] {x1, x2}) for (double z : new double[] {z1, z2}) {
            line(all, x, y1, z, x, y2, z, vertical, Layer.PILLAR);
            for (int layer = 1; layer < (int) Math.ceil(cornerDensityMultiplier); layer++) {
                double offset = Math.min(0.35, 0.14 * layer);
                double offsetX = x == x1 ? offset : -offset;
                double offsetZ = z == z1 ? offset : -offset;
                line(all, x + offsetX, y1, z + offsetZ, x + offsetX, y2, z + offsetZ,
                    vertical, Layer.PILLAR);
            }
        }

        if (sideGrid) addSideGrid(all, x1, y1, z1, x2, y2, z2, perimeter, vertical);
        if (floorGrid) addFloorGrid(all, x1, y1, z1, x2, z2, perimeter);
        marker(all, x1, y1, z1, Layer.POS1);
        marker(all, x2, y2, z2, Layer.POS2);

        int requested = all.size();
        List<Point> limited = all;
        if (all.size() > maxParticles) {
            limited = new ArrayList<>(maxParticles);
            double step = (double) all.size() / maxParticles;
            for (int i = 0; i < maxParticles; i++) limited.add(all.get(Math.min(all.size() - 1, (int) (i * step))));
        }
        int perimeterCount = count(limited, Layer.PERIMETER);
        int pillarCount = count(limited, Layer.PILLAR);
        int sideCount = count(limited, Layer.SIDE_GRID);
        int floorCount = count(limited, Layer.FLOOR_GRID);
        int markerCount = count(limited, Layer.POS1) + count(limited, Layer.POS2);
        return new RenderPlan(List.copyOf(limited), perimeter, requested, perimeterCount,
            pillarCount, sideCount, floorCount, markerCount);
    }

    private void addSideGrid(List<Point> out, double x1, double y1, double z1,
                             double x2, double y2, double z2, double horizontal, double vertical) {
        for (double y = y1 + sideGridSpacing; y < y2; y += sideGridSpacing) {
            line(out, x1, y, z1, x2, y, z1, horizontal, Layer.SIDE_GRID);
            line(out, x1, y, z2, x2, y, z2, horizontal, Layer.SIDE_GRID);
            line(out, x1, y, z1, x1, y, z2, horizontal, Layer.SIDE_GRID);
            line(out, x2, y, z1, x2, y, z2, horizontal, Layer.SIDE_GRID);
        }
        for (double x = x1 + sideGridSpacing; x < x2; x += sideGridSpacing) {
            line(out, x, y1, z1, x, y2, z1, vertical, Layer.SIDE_GRID);
            line(out, x, y1, z2, x, y2, z2, vertical, Layer.SIDE_GRID);
        }
        for (double z = z1 + sideGridSpacing; z < z2; z += sideGridSpacing) {
            line(out, x1, y1, z, x1, y2, z, vertical, Layer.SIDE_GRID);
            line(out, x2, y1, z, x2, y2, z, vertical, Layer.SIDE_GRID);
        }
    }

    private void addFloorGrid(List<Point> out, double x1, double y, double z1,
                              double x2, double z2, double spacing) {
        for (double x = x1 + floorGridSpacing; x < x2; x += floorGridSpacing)
            line(out, x, y, z1, x, y, z2, spacing, Layer.FLOOR_GRID);
        for (double z = z1 + floorGridSpacing; z < z2; z += floorGridSpacing)
            line(out, x1, y, z, x2, y, z, spacing, Layer.FLOOR_GRID);
    }

    private static void rectangle(List<Point> out, double x1, double y, double z1,
                                  double x2, double z2, double spacing, Layer layer) {
        line(out, x1, y, z1, x2, y, z1, spacing, layer);
        line(out, x1, y, z2, x2, y, z2, spacing, layer);
        line(out, x1, y, z1, x1, y, z2, spacing, layer);
        line(out, x2, y, z1, x2, y, z2, spacing, layer);
    }

    private static void line(List<Point> out, double x1, double y1, double z1,
                             double x2, double y2, double z2, double spacing, Layer layer) {
        double distance = Math.sqrt(Math.pow(x2 - x1, 2) + Math.pow(y2 - y1, 2) + Math.pow(z2 - z1, 2));
        int steps = Math.max(1, (int) Math.ceil(distance / spacing));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            out.add(new Point(x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, z1 + (z2 - z1) * t, layer));
        }
    }

    private static void marker(List<Point> out, double x, double y, double z, Layer layer) {
        double d = 0.18;
        out.add(new Point(x, y, z, layer));
        out.add(new Point(x + d, y, z, layer));
        out.add(new Point(x - d, y, z, layer));
        out.add(new Point(x, y + d, z, layer));
        out.add(new Point(x, y - d, z, layer));
        out.add(new Point(x, y, z + d, layer));
        out.add(new Point(x, y, z - d, layer));
    }

    private static int count(List<Point> points, Layer layer) {
        int count = 0;
        for (Point point : points) if (point.layer() == layer) count++;
        return count;
    }
}

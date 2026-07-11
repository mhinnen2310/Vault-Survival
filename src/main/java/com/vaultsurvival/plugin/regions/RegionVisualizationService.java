package com.vaultsurvival.plugin.regions;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Shared rendering engine for persisted regions and every existing cuboid selector. */
public final class RegionVisualizationService implements Listener {
    private static final Particle.DustOptions POS1_DUST = new Particle.DustOptions(Color.LIME, 1.5f);
    private static final Particle.DustOptions POS2_DUST = new Particle.DustOptions(Color.RED, 1.5f);

    private final VaultSurvivalPlugin plugin;
    private final RegionParticleRenderer renderer;
    private final RegionTypeStyleRegistry styles = new RegionTypeStyleRegistry();
    private final Map<UUID, RegionVisualizationSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, List<TextDisplay>> labels = new ConcurrentHashMap<>();
    private final boolean enabled;
    private final boolean labelsEnabled;
    private final boolean showPos1;
    private final boolean showPos2;
    private final boolean showRegionType;
    private final boolean showDimensions;
    private final boolean showVolume;
    private final int defaultDuration;
    private final int intervalTicks;
    private final int worldBudget;
    private final double cullDistanceSquared;
    private BukkitTask renderTask;

    public RegionVisualizationService(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        var config = plugin.getConfigManager().getConfig();
        this.renderer = new RegionParticleRenderer(config);
        String p = "regions.visualization.";
        enabled = config.getBoolean(p + "enabled", true);
        defaultDuration = config.getInt(p + "defaultDurationSeconds", 30);
        intervalTicks = Math.max(1, config.getInt(p + "particles.renderIntervalTicks", 5));
        worldBudget = Math.max(1000, config.getInt(p + "particles.maxParticlesPerTickPerWorld", 12000));
        double cullDistance = Math.max(32, config.getDouble(p + "particles.distanceCullingBlocks", 128));
        cullDistanceSquared = cullDistance * cullDistance;
        labelsEnabled = config.getBoolean(p + "labels.enabled", true);
        showPos1 = config.getBoolean(p + "labels.showPos1", true);
        showPos2 = config.getBoolean(p + "labels.showPos2", true);
        showRegionType = config.getBoolean(p + "labels.showRegionType", true);
        showDimensions = config.getBoolean(p + "labels.showDimensions", true);
        showVolume = config.getBoolean(p + "labels.showVolume", true);
    }

    public void start() {
        if (!enabled || renderTask != null) return;
        renderTask = Bukkit.getScheduler().runTaskTimer(plugin, this::renderAll, 1L, 2L);
    }

    public void shutdown() {
        if (renderTask != null) renderTask.cancel();
        renderTask = null;
        for (UUID viewer : List.copyOf(sessions.keySet())) hide(viewer);
    }

    public boolean showRegion(Player viewer, RegionData.Region region, RegionVisualizationSession.Mode mode,
                              boolean sharedNearby) {
        var world = Bukkit.getWorld(region.getWorldName());
        if (world == null || viewer.getWorld() != world) return false;
        return showBounds(viewer, new RegionVisualizationSession.Bounds(world,
            region.getX1(), region.getY1(), region.getZ1(), region.getX2(), region.getY2(), region.getZ2()),
            region.getType(), region.getName(), mode, sharedNearby);
    }

    public boolean showBounds(Player viewer, RegionVisualizationSession.Bounds bounds,
                              RegionData.RegionType type, String name,
                              RegionVisualizationSession.Mode mode, boolean sharedNearby) {
        if (!enabled || viewer.getWorld() != bounds.world()) return false;
        UUID id = viewer.getUniqueId();
        RegionVisualizationSession old = sessions.get(id);
        boolean sideGrid = old == null ? renderer.defaultSideGridEnabled() : old.sideGrid();
        boolean floorGrid = old == null ? renderer.defaultFloorGridEnabled() : old.floorGrid();
        RegionVisualizationSession session = new RegionVisualizationSession(id, bounds, type, name, mode,
            sideGrid, floorGrid, sharedNearby);
        session.renderPlan(renderer.build(bounds, sideGrid, floorGrid, session.density()));
        sessions.put(id, session);
        removeLabels(id);
        createLabels(session);
        render(session);
        return true;
    }

    public boolean refresh(Player viewer, RegionVisualizationSession.Bounds bounds,
                           RegionData.RegionType type, String name) {
        RegionVisualizationSession current = sessions.get(viewer.getUniqueId());
        RegionVisualizationSession.Mode mode = current == null
            ? RegionVisualizationSession.Mode.WHILE_EDITING : current.mode();
        boolean shared = current != null && current.sharedNearby();
        return showBounds(viewer, bounds, type, name, mode, shared);
    }

    public boolean hide(UUID viewerId) {
        RegionVisualizationSession removed = sessions.remove(viewerId);
        removeLabels(viewerId);
        return removed != null;
    }

    public Optional<RegionVisualizationSession> session(UUID viewerId) {
        return Optional.ofNullable(sessions.get(viewerId));
    }

    public boolean setMode(UUID viewerId, RegionVisualizationSession.Mode mode) {
        RegionVisualizationSession session = sessions.get(viewerId);
        if (session == null) return false;
        session.setMode(mode);
        return true;
    }

    public boolean setSideGrid(UUID viewerId, boolean enabled) {
        RegionVisualizationSession session = sessions.get(viewerId);
        if (session == null) return false;
        session.setSideGrid(enabled);
        session.renderPlan(renderer.build(session.bounds(), enabled, session.floorGrid()));
        return true;
    }

    public boolean setFloorGrid(UUID viewerId, boolean enabled) {
        RegionVisualizationSession session = sessions.get(viewerId);
        if (session == null) return false;
        session.setFloorGrid(enabled);
        session.renderPlan(renderer.build(session.bounds(), session.sideGrid(), enabled));
        return true;
    }

    public boolean setDensity(UUID viewerId, RegionVisualizationSession.Density density) {
        RegionVisualizationSession session=sessions.get(viewerId); if(session==null)return false;
        session.setDensity(density); session.renderPlan(renderer.build(session.bounds(),session.sideGrid(),session.floorGrid(),density)); return true;
    }

    public RegionParticleRenderer.RenderPlan debugPlan(RegionVisualizationSession.Bounds bounds,
                                                        boolean sideGrid, boolean floorGrid) {
        return renderer.build(bounds, sideGrid, floorGrid);
    }

    public RegionVisualizationSession.Mode defaultMode() {
        String configured = plugin.getConfigManager().getConfig().getString("regions.visualization.defaultMode", "WHILE_EDITING");
        RegionVisualizationSession.Mode mode = RegionVisualizationSession.Mode.parse(configured == null ? "editing" : configured);
        if (mode != null) return mode;
        return switch (defaultDuration) {
            case 10 -> RegionVisualizationSession.Mode.TEN_SECONDS;
            case 60 -> RegionVisualizationSession.Mode.SIXTY_SECONDS;
            default -> RegionVisualizationSession.Mode.THIRTY_SECONDS;
        };
    }

    private void renderAll() {
        Map<org.bukkit.World,Integer> worldCounts = new java.util.HashMap<>();
        for (RegionVisualizationSession session : List.copyOf(sessions.values())) {
            Player owner = Bukkit.getPlayer(session.viewerId());
            if (owner == null || !owner.isOnline() || owner.getWorld() != session.bounds().world() || session.expired()) {
                hide(session.viewerId());
                continue;
            }
            int tpsMultiplier = Bukkit.getTPS()[0] < 17 ? 4 : Bukkit.getTPS()[0] < 19 ? 2 : 1;
            long requiredMillis = session.inInitialPulse() ? 100L : intervalTicks * 50L * tpsMultiplier;
            if (System.currentTimeMillis() - session.lastRenderedAtMillis() < requiredMillis) continue;
            int remaining = worldBudget - worldCounts.getOrDefault(session.bounds().world(),0);
            if(remaining<=0) continue;
            int rendered=render(session,remaining); worldCounts.merge(session.bounds().world(),rendered,Integer::sum);
        }
    }

    private void render(RegionVisualizationSession session) {
        render(session,worldBudget);
    }

    private int render(RegionVisualizationSession session, int budget) {
        Player owner = Bukkit.getPlayer(session.viewerId());
        if (owner == null) return 0;
        List<Player> recipients = new ArrayList<>();
        recipients.add(owner);
        if (session.sharedNearby()) {
            Location center = center(session.bounds());
            for (Player player : session.bounds().world().getPlayers()) {
                if (!player.equals(owner) && player.getLocation().distanceSquared(center) <= 128 * 128) recipients.add(player);
            }
        }
        RegionTypeStyleRegistry.Style style = styles.style(session.type());
        int rendered=0;
        outer: for (Player recipient : recipients) {
            int pointIndex = 0;
            for (RegionParticleRenderer.Point point : session.renderPlan().points()) {
                if(rendered>=budget) break outer;
                double dx=recipient.getLocation().getX()-point.x(),dy=recipient.getLocation().getY()-point.y(),dz=recipient.getLocation().getZ()-point.z();
                double distance=dx*dx+dy*dy+dz*dz;
                if(distance>cullDistanceSquared && point.layer()!=RegionParticleRenderer.Layer.POS1 && point.layer()!=RegionParticleRenderer.Layer.POS2 && pointIndex++%4!=0) continue;
                Particle.DustOptions dust = switch (point.layer()) {
                    case POS1 -> POS1_DUST;
                    case POS2 -> POS2_DUST;
                    default -> style.dust();
                };
                if (session.type() == RegionData.RegionType.MINT
                    && point.layer() != RegionParticleRenderer.Layer.POS1
                    && point.layer() != RegionParticleRenderer.Layer.POS2
                    && pointIndex++ % 12 == 0) {
                    recipient.spawnParticle(Particle.END_ROD, point.x(), point.y(), point.z(), 1, 0, 0, 0, 0);
                } else {
                    recipient.spawnParticle(Particle.DUST, point.x(), point.y(), point.z(), session.inInitialPulse()?2:1, 0, 0, 0, 0, dust);
                }
                rendered++;
            }
            for (TextDisplay display : labels.getOrDefault(session.viewerId(), List.of())) {
                recipient.showEntity(plugin, display);
            }
        }
        session.renderedNow();
        return rendered;
    }

    private void createLabels(RegionVisualizationSession session) {
        if (!labelsEnabled) return;
        var b = session.bounds();
        List<TextDisplay> created = new ArrayList<>();
        if (showPos1) addLabel(created, new Location(b.world(), b.minX() + .5, b.minY() + 1.25, b.minZ() + .5),
            "POS1 " + b.minX() + ", " + b.minY() + ", " + b.minZ());
        if (showPos2) addLabel(created, new Location(b.world(), b.outerMaxX() - .5, b.outerMaxY() + .25, b.outerMaxZ() - .5),
            "POS2 " + b.maxX() + ", " + b.maxY() + ", " + b.maxZ());
        List<String> center = new ArrayList<>();
        if (showRegionType) center.add(session.displayName() + " [" + session.type() + "]");
        if (showDimensions) center.add(b.width() + " x " + b.height() + " x " + b.depth());
        if (showVolume) center.add(b.volume() + " blocks");
        if (!center.isEmpty()) addLabel(created, center(b).add(0, 1.5, 0), String.join("\n", center));
        labels.put(session.viewerId(), created);
    }

    private void addLabel(List<TextDisplay> target, Location location, String text) {
        if (location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            target.add(label(location, text));
        }
    }

    private TextDisplay label(Location location, String text) {
        return location.getWorld().spawn(location, TextDisplay.class, display -> {
            display.text(Component.text(text));
            display.setBillboard(Display.Billboard.CENTER);
            display.setSeeThrough(true);
            display.setShadowed(true);
            display.setDefaultBackground(false);
            display.setViewRange(128f);
            display.setPersistent(false);
            display.setVisibleByDefault(false);
        });
    }

    private void removeLabels(UUID viewerId) {
        List<TextDisplay> removed = labels.remove(viewerId);
        for (TextDisplay label : removed == null ? List.<TextDisplay>of() : removed) {
            label.remove();
        }
    }

    private static Location center(RegionVisualizationSession.Bounds b) {
        return new Location(b.world(), (b.minX() + b.outerMaxX()) / 2,
            (b.minY() + b.outerMaxY()) / 2, (b.minZ() + b.outerMaxZ()) / 2);
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { hide(event.getPlayer().getUniqueId()); }
    @EventHandler public void onWorldChange(PlayerChangedWorldEvent event) { hide(event.getPlayer().getUniqueId()); }
}

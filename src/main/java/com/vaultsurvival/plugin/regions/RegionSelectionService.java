package com.vaultsurvival.plugin.regions;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Shared state for the region wand only; VWE and district selectors retain their domain state. */
public final class RegionSelectionService {
    public record Selection(Location pos1, Location pos2, RegionData.RegionType type) {
        public boolean complete() { return pos1 != null && pos2 != null && pos1.getWorld() == pos2.getWorld(); }
        public RegionVisualizationSession.Bounds bounds() {
            if (!complete()) throw new IllegalStateException("selection is incomplete");
            return new RegionVisualizationSession.Bounds(pos1.getWorld(),
                Math.min(pos1.getBlockX(), pos2.getBlockX()), Math.min(pos1.getBlockY(), pos2.getBlockY()), Math.min(pos1.getBlockZ(), pos2.getBlockZ()),
                Math.max(pos1.getBlockX(), pos2.getBlockX()), Math.max(pos1.getBlockY(), pos2.getBlockY()), Math.max(pos1.getBlockZ(), pos2.getBlockZ()));
        }
    }

    private static final class MutableSelection {
        private Location pos1;
        private Location pos2;
        private RegionData.RegionType type = RegionData.RegionType.CUSTOM;
    }

    private final RegionVisualizationService visualization;
    private final Map<UUID, MutableSelection> selections = new ConcurrentHashMap<>();

    public RegionSelectionService(RegionVisualizationService visualization) {
        this.visualization = visualization;
    }

    public Selection setPoint(Player player, Location location, boolean pos1) {
        MutableSelection selection = selections.computeIfAbsent(player.getUniqueId(), ignored -> new MutableSelection());
        if (pos1) selection.pos1 = location.clone(); else selection.pos2 = location.clone();
        Selection snapshot = snapshot(selection);
        visualizeIfComplete(player, snapshot);
        return snapshot;
    }

    public Selection setType(Player player, RegionData.RegionType type) {
        MutableSelection selection = selections.computeIfAbsent(player.getUniqueId(), ignored -> new MutableSelection());
        selection.type = type;
        Selection snapshot = snapshot(selection);
        visualizeIfComplete(player, snapshot);
        return snapshot;
    }

    public Optional<Selection> get(UUID playerId) {
        MutableSelection selection = selections.get(playerId);
        return selection == null ? Optional.empty() : Optional.of(snapshot(selection));
    }

    public void clear(UUID playerId) {
        selections.remove(playerId);
        visualization.hide(playerId);
    }

    private void visualizeIfComplete(Player player, Selection selection) {
        if (selection.complete()) visualization.showBounds(player, selection.bounds(), selection.type(),
            "Unsaved selection", RegionVisualizationSession.Mode.WHILE_EDITING, false);
    }

    private static Selection snapshot(MutableSelection selection) {
        return new Selection(selection.pos1 == null ? null : selection.pos1.clone(),
            selection.pos2 == null ? null : selection.pos2.clone(), selection.type);
    }
}

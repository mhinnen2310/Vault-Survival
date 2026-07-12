package com.vaultsurvival.plugin.vsworldedit;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Deterministic coordinate-based cycling; identical coordinates always resolve identically. */
public final class GridBlockPattern implements BlockPattern {
    private final List<Entry> entries;
    private final List<Material> cycle;

    public GridBlockPattern(List<Entry> entries) {
        this.entries = List.copyOf(entries);
        if (entries.isEmpty() || entries.stream().anyMatch(entry -> entry.weight() <= 0))
            throw new IllegalArgumentException("positive grid entries are required");
        cycle = new ArrayList<>();
        int total = entries.stream().mapToInt(Entry::weight).sum();
        int[] current = new int[entries.size()];
        // Smooth weighted round-robin spreads entries instead of creating large material bands.
        for (int slot = 0; slot < total; slot++) {
            int selected = 0;
            for (int i = 0; i < entries.size(); i++) {
                current[i] += entries.get(i).weight();
                if (current[i] > current[selected]) selected = i;
            }
            cycle.add(entries.get(selected).material());
            current[selected] -= total;
        }
    }

    @Override public Material materialAt(int x, int y, int z, Random random) {
        return cycle.get(Math.floorMod(x + y + z, cycle.size()));
    }
    @Override public Mode mode() { return Mode.GRID; }
    @Override public List<Entry> entries() { return entries; }
}

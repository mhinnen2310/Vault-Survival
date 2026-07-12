package com.vaultsurvival.plugin.vsworldedit;

import org.bukkit.Material;

import java.util.List;
import java.util.Random;

/** Random distribution using validated positive integer weights. */
public final class WeightedBlockPattern implements BlockPattern {
    private final List<Entry> entries;
    private final int totalWeight;

    public WeightedBlockPattern(List<Entry> entries) {
        this.entries = List.copyOf(entries);
        if (entries.isEmpty() || entries.stream().anyMatch(entry -> entry.weight() <= 0))
            throw new IllegalArgumentException("positive weighted entries are required");
        totalWeight = entries.stream().mapToInt(Entry::weight).sum();
    }

    @Override public Material materialAt(int x, int y, int z, Random random) {
        int selected = random.nextInt(totalWeight);
        int cursor = 0;
        for (Entry entry : entries) {
            cursor += entry.weight();
            if (selected < cursor) return entry.material();
        }
        return entries.getLast().material();
    }
    @Override public Mode mode() { return Mode.WEIGHTED_RANDOM; }
    @Override public List<Entry> entries() { return entries; }
}

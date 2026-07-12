package com.vaultsurvival.plugin.vsworldedit;

import org.bukkit.Material;

import java.util.List;
import java.util.Random;

/** Equal random distribution. */
public final class RandomBlockPattern implements BlockPattern {
    private final List<Entry> entries;

    public RandomBlockPattern(List<Material> materials) {
        entries = materials.stream().map(material -> new Entry(material, 1)).toList();
        if (entries.isEmpty()) throw new IllegalArgumentException("materials are required");
    }

    @Override public Material materialAt(int x, int y, int z, Random random) {
        return entries.get(random.nextInt(entries.size())).material();
    }
    @Override public Mode mode() { return Mode.RANDOM; }
    @Override public List<Entry> entries() { return entries; }
}

package com.vaultsurvival.plugin.vsworldedit;

import org.bukkit.Material;

import java.util.List;
import java.util.Random;

public final class SingleBlockPattern implements BlockPattern {
    private final List<Entry> entries;

    public SingleBlockPattern(Material material) { entries = List.of(new Entry(material, 100)); }
    @Override public Material materialAt(int x, int y, int z, Random random) { return entries.getFirst().material(); }
    @Override public Mode mode() { return Mode.SINGLE; }
    @Override public List<Entry> entries() { return entries; }
}

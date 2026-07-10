package com.vaultsurvival.plugin.vsworldedit;

import org.bukkit.Material;

import java.util.List;
import java.util.Random;

/** Validated material source for a coordinate in a VWE operation. */
public interface BlockPattern {
    enum Mode { SINGLE, RANDOM, WEIGHTED_RANDOM, GRID }
    record Entry(Material material, int weight) {}

    Material materialAt(int x, int y, int z, Random random);
    Mode mode();
    List<Entry> entries();

    default boolean containsAir() {
        return entries().stream().anyMatch(entry -> entry.material().isAir());
    }

    default String describe() {
        return mode() + " " + entries().stream()
            .map(entry -> entry.weight() + "x " + entry.material().getKey())
            .reduce((left, right) -> left + ", " + right).orElse("empty");
    }
}

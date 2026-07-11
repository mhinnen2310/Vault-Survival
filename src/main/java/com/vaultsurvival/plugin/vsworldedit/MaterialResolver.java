package com.vaultsurvival.plugin.vsworldedit;

import org.bukkit.Material;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/** Strict vanilla material resolver with explicit air aliases and typo suggestions. */
public final class MaterialResolver {
    public record ResolveResult(Material material, String error, String suggestion) {
        public boolean valid() { return material != null; }
    }

    private final boolean allowNumericAirAlias;
    private final boolean requireBlockValidation;

    public MaterialResolver(boolean allowNumericAirAlias) {
        this(allowNumericAirAlias,true);
    }

    MaterialResolver(boolean allowNumericAirAlias, boolean requireBlockValidation) {
        this.allowNumericAirAlias = allowNumericAirAlias;
        this.requireBlockValidation = requireBlockValidation;
    }

    public ResolveResult resolve(String input) {
        if (input == null || input.isBlank()) return invalid("Material may not be empty.", null);
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("0")) {
            if (allowNumericAirAlias) return valid(Material.AIR);
            return invalid("Numeric air alias 0 is disabled.", "Use air or minecraft:air.");
        }
        int colon = normalized.indexOf(':');
        if (colon >= 0 && !normalized.substring(0, colon).equals("minecraft")) {
            return invalid("Only vanilla minecraft materials are supported: " + input, null);
        }
        Material material = Material.matchMaterial(normalized);
        if (material == null && normalized.startsWith("minecraft:")) {
            material = Material.matchMaterial(normalized.substring("minecraft:".length()));
        }
        if (material == null || (requireBlockValidation && !material.isBlock() && !material.isAir())) {
            String candidate = normalized.startsWith("minecraft:") ? normalized.substring("minecraft:".length()) : normalized;
            String suggestion = closest(candidate);
            return invalid("Unknown material: " + input, suggestion == null ? null : "Did you mean: " + suggestion + "?");
        }
        return valid(material);
    }

    private String closest(String input) {
        return Arrays.stream(Material.values())
            .filter(material -> !requireBlockValidation || material.isBlock() || material.isAir())
            .map(material -> material.name().toLowerCase(Locale.ROOT))
            .min(Comparator.comparingInt(candidate -> distance(input, candidate)))
            .filter(candidate -> distance(input, candidate) <= Math.max(2, input.length() / 3))
            .orElse(null);
    }

    private static int distance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            previous = current;
        }
        return previous[right.length()];
    }

    private static ResolveResult valid(Material material) { return new ResolveResult(material, null, null); }
    private static ResolveResult invalid(String error, String suggestion) { return new ResolveResult(null, error, suggestion); }
}

package com.vaultsurvival.plugin.vsworldedit;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Parses documented VWE single, random, weighted and deterministic grid syntax. */
public final class PatternParser {
    private static final String EXAMPLES = "Accepted examples: stone, minecraft:stone, air, 0";
    private final MaterialResolver materials;
    private final boolean normalizePercentages;
    private final boolean allowLegacyCommaWeights;
    private final int maxEntries;
    private final BlockPattern.Mode defaultMode;

    public PatternParser(FileConfiguration config) {
        String root = config.isConfigurationSection("vsWorldEdit.patterns") ? "vsWorldEdit" : "vsworldedit";
        String p = root + ".patterns.";
        materials = new MaterialResolver(config.getBoolean(p + "allowNumericAirAlias", true));
        normalizePercentages = config.getBoolean(p + "normalizePercentages", true);
        allowLegacyCommaWeights = config.getBoolean(p + "allowLegacyCommaWeights", true);
        maxEntries = Math.max(1, config.getInt(p + "maxPatternEntries",
            config.getInt(p + "maxEntries", 32)));
        BlockPattern.Mode configured;
        try {
            String configuredValue = config.getString(p + "defaultMode", "RANDOM");
            configured = BlockPattern.Mode.valueOf((configuredValue == null ? "RANDOM" : configuredValue).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) { configured = BlockPattern.Mode.RANDOM; }
        defaultMode = configured;
    }

    public MaterialResolver materialResolver() { return materials; }
    public PatternValidationResult parse(String expression) { return parse(expression, null); }

    /** The second expression is only used by unambiguous legacy `50,50 stone,cobble` syntax. */
    public PatternValidationResult parse(String expression, String legacyMaterials) {
        if (expression == null || expression.isBlank()) return PatternValidationResult.invalid("Pattern may not be empty. " + EXAMPLES);
        String value = expression.trim();
        if (legacyMaterials == null) {
            int separator = firstWhitespace(value);
            if (separator > 0 && looksLikeBareWeights(value.substring(0, separator))) {
                legacyMaterials = value.substring(separator).trim();
                value = value.substring(0, separator);
            }
        }
        if (looksLikeBareWeights(value)) {
            if (legacyMaterials == null || legacyMaterials.isBlank()) {
                return PatternValidationResult.invalid("Weights without materials are not a pattern: " + value,
                    "Use 50%stone,50%cobblestone or 50,50 stone,cobblestone.");
            }
            if (!allowLegacyCommaWeights) return PatternValidationResult.invalid("Legacy comma weights are disabled.");
            return parseLegacyWeights(value, legacyMaterials);
        }
        if (legacyMaterials != null && !legacyMaterials.isBlank()) {
            return PatternValidationResult.invalid("Unexpected extra pattern argument: " + legacyMaterials,
                "Use comma-separated materials in one argument.");
        }

        BlockPattern.Mode explicitMode = null;
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("grid:")) { explicitMode = BlockPattern.Mode.GRID; value = value.substring(5); }
        else if (lower.startsWith("random:")) { explicitMode = BlockPattern.Mode.RANDOM; value = value.substring(7); }

        String[] rawEntries = value.split(",", -1);
        PatternValidationResult sizeError = validateEntryCount(rawEntries);
        if (sizeError != null) return sizeError;
        boolean anyPercent = false;
        boolean allPercent = true;
        List<Material> resolved = new ArrayList<>();
        List<Double> percentages = new ArrayList<>();
        for (String rawEntry : rawEntries) {
            String entry = rawEntry.trim();
            if (entry.isEmpty()) return PatternValidationResult.invalid("Pattern contains an empty entry.");
            int percentAt = entry.indexOf('%');
            anyPercent |= percentAt >= 0;
            allPercent &= percentAt >= 0;
            String materialText = percentAt >= 0 ? entry.substring(percentAt + 1).trim() : entry;
            if (percentAt >= 0) {
                Double percentage = parsePercentage(entry.substring(0, percentAt).trim());
                if (percentage == null) return PatternValidationResult.invalid("Invalid or negative percentage: " + entry);
                percentages.add(percentage);
            }
            var material = materials.resolve(materialText);
            if (!material.valid()) return PatternValidationResult.invalid(material.error() + ". " + EXAMPLES, material.suggestion());
            resolved.add(material.material());
        }
        if (anyPercent && !allPercent) return PatternValidationResult.invalid("Either every pattern entry must have a percentage, or none may have one.");
        if (resolved.size() == 1 && explicitMode == null && !anyPercent) return PatternValidationResult.valid(new SingleBlockPattern(resolved.getFirst()));

        if (anyPercent) {
            var weights = normalize(resolved, percentages);
            if (weights == null) return PatternValidationResult.invalid("Pattern percentages must total 100%. Current total: " + sum(percentages) + "%.");
            return PatternValidationResult.valid(explicitMode == BlockPattern.Mode.GRID
                ? new GridBlockPattern(weights) : new WeightedBlockPattern(weights));
        }
        BlockPattern.Mode mode = explicitMode == null ? defaultMode : explicitMode;
        if (mode == BlockPattern.Mode.GRID) return PatternValidationResult.valid(new GridBlockPattern(equalWeights(resolved)));
        if (mode == BlockPattern.Mode.WEIGHTED_RANDOM) return PatternValidationResult.valid(new WeightedBlockPattern(equalWeights(resolved)));
        return PatternValidationResult.valid(new RandomBlockPattern(resolved));
    }

    private PatternValidationResult parseLegacyWeights(String weightsExpression, String materialsExpression) {
        String[] rawWeights = weightsExpression.split(",", -1);
        String[] rawMaterials = materialsExpression.split(",", -1);
        PatternValidationResult sizeError = validateEntryCount(rawMaterials);
        if (sizeError != null) return sizeError;
        if (rawWeights.length != rawMaterials.length) return PatternValidationResult.invalid("Legacy weights must match the number of materials.");
        List<Material> resolved = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        for (int i = 0; i < rawMaterials.length; i++) {
            Double weight = parsePercentage(rawWeights[i].trim());
            if (weight == null) return PatternValidationResult.invalid("Invalid or negative weight: " + rawWeights[i]);
            var material = materials.resolve(rawMaterials[i].trim());
            if (!material.valid()) return PatternValidationResult.invalid(material.error() + ". " + EXAMPLES, material.suggestion());
            weights.add(weight);
            resolved.add(material.material());
        }
        List<BlockPattern.Entry> normalized = normalize(resolved, weights);
        if (normalized == null) return PatternValidationResult.invalid("Legacy weights must total 100, unless normalization is enabled.");
        return PatternValidationResult.valid(new WeightedBlockPattern(normalized));
    }

    private PatternValidationResult validateEntryCount(String[] entries) {
        if (entries.length == 0) return PatternValidationResult.invalid("Pattern may not be empty.");
        if (entries.length > maxEntries) return PatternValidationResult.invalid("Pattern has " + entries.length + " entries; maximum is " + maxEntries + ".");
        return null;
    }

    private List<BlockPattern.Entry> normalize(List<Material> resolved, List<Double> values) {
        double total = sum(values);
        if (total <= 0 || (!normalizePercentages && Math.abs(total - 100.0) > 0.001)) return null;
        final int scale = 1000;
        List<BlockPattern.Entry> entries = new ArrayList<>();
        int assigned = 0;
        for (int i = 0; i < resolved.size(); i++) {
            int weight = Math.max(1, (int) Math.round(values.get(i) * scale / total));
            assigned += weight;
            entries.add(new BlockPattern.Entry(resolved.get(i), weight));
        }
        int difference = scale - assigned;
        int largest = 0;
        for (int i = 1; i < entries.size(); i++) if (entries.get(i).weight() > entries.get(largest).weight()) largest = i;
        BlockPattern.Entry entry = entries.get(largest);
        if (entry.weight() + difference <= 0) return null;
        entries.set(largest, new BlockPattern.Entry(entry.material(), entry.weight() + difference));
        return entries;
    }

    private static List<BlockPattern.Entry> equalWeights(List<Material> materials) {
        return materials.stream().map(material -> new BlockPattern.Entry(material, 1)).toList();
    }
    private static boolean looksLikeBareWeights(String value) { return value.matches("[+-]?\\d+(?:\\.\\d+)?(?:,[+-]?\\d+(?:\\.\\d+)?)+"); }
    private static int firstWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) if (Character.isWhitespace(value.charAt(i))) return i;
        return -1;
    }
    private static Double parsePercentage(String value) {
        try {
            double parsed = Double.parseDouble(value);
            return !Double.isFinite(parsed) || parsed <= 0 ? null : parsed;
        }
        catch (NumberFormatException exception) { return null; }
    }
    private static double sum(List<Double> values) { return values.stream().mapToDouble(Double::doubleValue).sum(); }
}

package com.vaultsurvival.plugin.vsworldedit;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.List;

/** Live-server acceptance checks; Paper's dynamic material registry requires an initialized server. */
public final class PatternParserDiagnostics {
    public record Result(int checks, List<String> failures) {
        public boolean passed() { return failures.isEmpty(); }
    }

    private PatternParserDiagnostics() {}

    public static Result runDefaults() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("vsWorldEdit.patterns.allowNumericAirAlias", true);
        config.set("vsWorldEdit.patterns.normalizePercentages", true);
        config.set("vsWorldEdit.patterns.maxPatternEntries", 32);
        config.set("vsWorldEdit.patterns.defaultMode", "RANDOM");
        config.set("vsWorldEdit.patterns.allowLegacyCommaWeights", true);
        return run(new PatternParser(config));
    }

    public static Result run(PatternParser parser) {
        List<String> failures = new ArrayList<>();
        int checks = 0;
        checks += check(failures, parser.materialResolver().resolve("air").material() == Material.AIR, "air alias");
        checks += check(failures, parser.materialResolver().resolve("minecraft:air").material() == Material.AIR, "namespaced air");
        checks += check(failures, parser.materialResolver().resolve("0").material() == Material.AIR, "numeric air");
        checks += check(failures, parser.materialResolver().resolve("cave_air").material() == Material.CAVE_AIR, "cave air");
        checks += check(failures, parser.materialResolver().resolve("minecraft:void_air").material() == Material.VOID_AIR, "void air");
        checks += check(failures, parser.materialResolver().resolve("minecraft:stone").material() == Material.STONE, "namespaced stone");
        checks += check(failures, validMode(parser.parse("stone,cobblestone"), BlockPattern.Mode.RANDOM), "equal random");
        checks += check(failures, validMode(parser.parse("random:stone,cobblestone"), BlockPattern.Mode.RANDOM), "explicit random");
        checks += check(failures, validMode(parser.parse("50%stone,50%cobblestone"), BlockPattern.Mode.WEIGHTED_RANDOM), "weighted random");
        PatternValidationResult gridResult = parser.parse("grid:stone,cobblestone");
        checks += check(failures, validMode(gridResult, BlockPattern.Mode.GRID), "grid mode");
        if (gridResult.valid()) {
            BlockPattern grid = gridResult.pattern();
            checks += check(failures, grid.materialAt(0, 0, 0, null) == Material.STONE, "grid A 0,0");
            checks += check(failures, grid.materialAt(1, 0, 0, null) == Material.COBBLESTONE, "grid B 1,0");
            checks += check(failures, grid.materialAt(0, 0, 1, null) == Material.COBBLESTONE, "grid B 0,1");
            checks += check(failures, grid.materialAt(1, 0, 1, null) == Material.STONE, "grid A 1,1");
            checks += check(failures, grid.materialAt(-17, 4, 33, null) == grid.materialAt(-17, 4, 33, null), "negative coordinate determinism");
        }
        PatternValidationResult weightedGrid = parser.parse("grid:50%stone,50%cobblestone");
        checks += check(failures, validMode(weightedGrid, BlockPattern.Mode.GRID), "weighted grid");
        if (weightedGrid.valid()) {
            checks += check(failures, weightedGrid.pattern().materialAt(0, 0, 0, null) != weightedGrid.pattern().materialAt(1, 0, 0, null), "50/50 grid checker");
        }
        checks += check(failures, parser.parse("grid:stone,cobblestone,andesite").valid(), "three material grid");
        checks += check(failures, !parser.parse("50,50").valid(), "bare weights rejected");
        checks += check(failures, parser.parse("50,50", "stone,cobblestone").valid(), "split legacy syntax");
        checks += check(failures, parser.parse("50,50 stone,cobblestone").valid(), "single expression legacy syntax");
        checks += check(failures, !parser.parse("stone,,cobblestone").valid(), "empty entry rejected");
        checks += check(failures, !parser.parse("-10%stone,110%cobblestone").valid(), "negative rejected");
        checks += check(failures, !parser.parse("NaN%stone,100%cobblestone").valid(), "NaN rejected");
        checks += check(failures, !parser.parse("Infinity%stone,1%cobblestone").valid(), "infinity rejected");
        PatternValidationResult typo = parser.parse("ston");
        checks += check(failures, !typo.valid() && typo.error().contains("Unknown material: ston")
            && typo.suggestion() != null && typo.suggestion().contains("stone"), "typo suggestion");
        checks += check(failures, !parser.materialResolver().resolve("custom:stone").valid(), "non-vanilla namespace");
        return new Result(checks, List.copyOf(failures));
    }

    private static boolean validMode(PatternValidationResult result, BlockPattern.Mode mode) {
        return result.valid() && result.pattern().mode() == mode;
    }
    private static int check(List<String> failures, boolean condition, String name) {
        if (!condition) failures.add(name);
        return 1;
    }
}

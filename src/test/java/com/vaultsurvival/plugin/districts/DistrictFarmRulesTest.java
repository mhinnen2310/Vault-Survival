package com.vaultsurvival.plugin.districts;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DistrictFarmRulesTest {
    @Test void normalizesAndValidatesNames() {
        assertEquals("North Farm", DistrictFarmRules.validateName("  North   Farm "));
        assertThrows(IllegalArgumentException.class, () -> DistrictFarmRules.validateName("x"));
        assertThrows(IllegalArgumentException.class, () -> DistrictFarmRules.validateName("bad.name"));
    }

    @Test void enforcesConfiguredFootprintFraction() {
        var claim = new DistrictData.BlockClaim("world", 0, 0, 99, 99);
        var accepted = new DistrictFarmService.FarmZone("world", 0, 60, 0, 49, 70, 49);
        var rejected = new DistrictFarmService.FarmZone("world", 0, 60, 0, 50, 70, 49);
        assertDoesNotThrow(() -> DistrictFarmRules.requireAllowedFootprint(accepted, claim, .25));
        assertThrows(IllegalArgumentException.class, () -> DistrictFarmRules.requireAllowedFootprint(rejected, claim, .25));
    }

    @Test void levelConfigurationIsBounded() {
        var values = List.of(1, 2, 3, 4, 6);
        assertEquals(1, DistrictFarmRules.levelValue(values, 0, 1));
        assertEquals(6, DistrictFarmRules.levelValue(values, 99, 1));
        assertEquals(192, DistrictFarmRules.levelValue(List.of(), 3, 64));
    }
}

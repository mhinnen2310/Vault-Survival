package com.vaultsurvival.plugin.regions;

import org.bukkit.Color;
import org.bukkit.Particle;

import java.util.EnumMap;
import java.util.Map;

/** Recognizable, version-safe DUST styles for every selectable region type. */
public final class RegionTypeStyleRegistry {
    public record Style(Color color, float size) {
        public Particle.DustOptions dust() { return new Particle.DustOptions(color, size); }
    }

    private final Map<RegionData.RegionType, Style> styles = new EnumMap<>(RegionData.RegionType.class);
    private final Style fallback = style(255, 220, 40, 1.15f);

    public RegionTypeStyleRegistry() {
        for (RegionData.RegionType type : RegionData.RegionType.values()) styles.put(type, fallback);
        put(RegionData.RegionType.DISTRICT, 255, 200, 0, 1.25f);
        put(RegionData.RegionType.DISTRICT_MARKET, 30, 255, 70, 1.25f);
        put(RegionData.RegionType.DISTRICT_PUBLIC, 30, 255, 70, 1.25f);
        put(RegionData.RegionType.SPAWN_CITY, 0, 230, 255, 1.25f);
        put(RegionData.RegionType.SPAWN_PUBLIC, 0, 230, 255, 1.25f);
        put(RegionData.RegionType.MINT, 255, 215, 0, 1.45f);
        put(RegionData.RegionType.AUCTION_HALL, 255, 125, 20, 1.3f);
        put(RegionData.RegionType.STATION_PLATFORM, 45, 115, 255, 1.25f);
        put(RegionData.RegionType.DISTRICT_STATION, 45, 115, 255, 1.25f);
        put(RegionData.RegionType.STATION_ARRIVAL, 90, 205, 255, 1.25f);
        put(RegionData.RegionType.STATION_ROUTE, 90, 205, 255, 1.25f);
        put(RegionData.RegionType.TOWN_HALL, 245, 245, 245, 1.25f);
        put(RegionData.RegionType.JAIL, 255, 45, 45, 1.3f);
        put(RegionData.RegionType.POLICE_STATION, 20, 45, 175, 1.3f);
        put(RegionData.RegionType.TREASURY, 155, 70, 230, 1.3f);
        put(RegionData.RegionType.VAULT_ZONE, 115, 20, 90, 1.35f);
        put(RegionData.RegionType.REPAIR_ZONE, 125, 255, 40, 1.25f);
        put(RegionData.RegionType.JOB_BOARD, 0, 245, 220, 1.25f);
        put(RegionData.RegionType.BLACK_MARKET, 75, 15, 110, 1.35f);
        put(RegionData.RegionType.PROJECT_REGION, 255, 80, 190, 1.25f);
        put(RegionData.RegionType.TRAIN_INTERIOR, 145, 145, 145, 1.25f);
        put(RegionData.RegionType.FARM_ZONE, 80, 220, 70, 1.25f);
        put(RegionData.RegionType.NO_BREACH_ZONE, 255, 65, 65, 1.3f);
        put(RegionData.RegionType.NO_CASH_DROP, 170, 45, 45, 1.3f);
        put(RegionData.RegionType.ROAD, 185, 185, 185, 1.15f);
        put(RegionData.RegionType.OUTLANDS, 125, 95, 60, 1.15f);
    }

    private void put(RegionData.RegionType type, int red, int green, int blue, float size) {
        styles.put(type, style(red, green, blue, size));
    }

    private static Style style(int red, int green, int blue, float size) {
        return new Style(Color.fromRGB(red, green, blue), size);
    }

    public Style style(RegionData.RegionType type) { return styles.getOrDefault(type, fallback); }
}

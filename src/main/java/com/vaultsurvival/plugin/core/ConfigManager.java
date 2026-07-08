package com.vaultsurvival.plugin.core;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Central configuration manager.
 * Loads config.yml from disk, merges with defaults from the JAR,
 * and provides typed accessors for all configuration values.
 */
public class ConfigManager {

    private final Logger logger;
    private final File configFile;
    private FileConfiguration config;

    public ConfigManager(Logger logger, File dataFolder) {
        this.logger = logger;
        this.configFile = new File(dataFolder, "config.yml");
    }

    /**
     * Load or create config.yml. Merges in default values from resources.
     */
    public void load(InputStream defaultConfigStream) {
        if (!configFile.exists()) {
            configFile.getParentFile().mkdirs();
            try {
                if (defaultConfigStream != null) {
                    java.nio.file.Files.copy(
                        defaultConfigStream,
                        configFile.toPath()
                    );
                    logger.info("Created default config.yml");
                } else {
                    configFile.createNewFile();
                }
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Failed to create config.yml", e);
            }
        }

        config = YamlConfiguration.loadConfiguration(configFile);

        if (defaultConfigStream != null) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                new InputStreamReader(defaultConfigStream, StandardCharsets.UTF_8)
            );
            config.setDefaults(defaults);
            config.options().copyDefaults(true);
            save();
        }
    }

    /**
     * Reload configuration from disk (merges defaults).
     */
    public void reload() {
        load(null);
    }

    /**
     * Reload configuration from disk and merge defaults from the JAR.
     */
    public void reload(InputStream defaultConfigStream) {
        load(defaultConfigStream);
    }

    /**
     * Save current configuration to disk.
     */
    public void save() {
        try {
            config.save(configFile);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to save config.yml", e);
        }
    }

    /**
     * Get the raw FileConfiguration for direct access.
     */
    public FileConfiguration getConfig() {
        return config;
    }

    // --- Typed accessors for common config sections ---

    // Database (SQLite)
    public String getDatabaseFile() { return config.getString("database.file", "vault_survival.db"); }

    // Redis
    public boolean isRedisEnabled() { return config.getBoolean("redis.enabled", false); }
    public String getRedisHost() { return config.getString("redis.host", "localhost"); }
    public int getRedisPort() { return config.getInt("redis.port", 6379); }
    public String getRedisPassword() { return config.getString("redis.password", ""); }

    // General
    public String getServerName() { return config.getString("server.name", "Vault Survival"); }
    public String getChatPrefix() { return config.getString("server.chat_prefix", "&8[&6VS&8]"); }
    public boolean isDebugMode() { return config.getBoolean("server.debug", false); }
    
    // Spawn
    public String getSpawnCityName() { return config.getString("spawn.cityName", "Spawn City"); }
    public void setSpawnCityName(String name) { config.set("spawn.cityName", name); save(); }
    public String getSpawnWorld() { return config.getString("spawn.world", "world"); }
    public double getSpawnX() { return config.getDouble("spawn.x", 0.0); }
    public double getSpawnY() { return config.getDouble("spawn.y", 64.0); }
    public double getSpawnZ() { return config.getDouble("spawn.z", 0.0); }
    public float getSpawnYaw() { return (float) config.getDouble("spawn.yaw", 0.0); }
    public float getSpawnPitch() { return (float) config.getDouble("spawn.pitch", 0.0); }
    public void setSpawnLocation(String world, double x, double y, double z, float yaw, float pitch) {
        config.set("spawn.world", world);
        config.set("spawn.x", x);
        config.set("spawn.y", y);
        config.set("spawn.z", z);
        config.set("spawn.yaw", (double) yaw);
        config.set("spawn.pitch", (double) pitch);
        save();
    }

    // Chat
    public boolean isChatEnabled() { return config.getBoolean("chat.enabled", true); }
    public String getChatFormat() { return config.getString("chat.format", "%rank_label% &8┃ %district_label% &8┃ %player_name% &8> &f%message%"); }
    public String getChatPlayerNameColor() { return config.getString("chat.playerNameColor", "&c"); }
    public String getChatRankLabel(String groupName) {
        String safeGroup = groupName != null && !groupName.isBlank() ? groupName.toLowerCase() : "default";
        return config.getString("chat.rankLabels." + safeGroup, config.getString("chat.rankLabels.default", "&7PLAYER"));
    }
    public boolean isDistrictChatEnabled() { return config.getBoolean("chat.district.enabled", true); }
    public String getDistrictLabelFormat() { return config.getString("chat.district.format", "&e%district%"); }
    public String getNoDistrictLabel() { return config.getString("chat.district.noDistrict", config.getString("chat.noDistrictLabel", "&7OUTLANDS")); }
    public boolean isChatTabPrefix() { return config.getBoolean("chat.tab_prefix", true); }
    public boolean isChatNametagPrefix() { return config.getBoolean("chat.nametag_prefix", true); }

    // Currency
    public String getCurrencyName() { return config.getString("currency.name", "Coin"); }
    public String getCurrencyNamePlural() { return config.getString("currency.name_plural", "Coins"); }
    public int getCashModelData() { return config.getInt("currency.custom_model_data", 1001); }
    public String getCashMaterial() { return config.getString("currency.material", "GOLD_NUGGET"); }

    // Vaults
    public int getVaultBreachCooldownMinutes() { return config.getInt("vaults.breach_cooldown_minutes", 60); }
    public double getVaultMaxBreachPercent() { return config.getDouble("vaults.max_breach_percent", 50.0); }
    public String getVaultMaterial() { return config.getString("vaults.vault_material", "BARREL"); }

    // Staff Mode
    public boolean isStaffModeSeparateInventories() { return config.getBoolean("staffmode.separate_inventories", true); }
    public String getStaffModeVisEffect() { return config.getString("staffmode.visibility_effect", "GLOWING"); }
    public String getStaffModeGlowColor() { return config.getString("staffmode.glow_color", "RED"); }
    public String getStaffModePrefix() { return config.getString("staffmode.staffmode_prefix", "&c[STAFF]"); }
    public boolean isStaffModeAllowContainers() { return config.getBoolean("staffmode.allow_container_interact", false); }
    public boolean isStaffModeAllowDrop() { return config.getBoolean("staffmode.allow_item_drop", false); }
    public boolean isStaffModeAllowPickup() { return config.getBoolean("staffmode.allow_item_pickup", false); }
    public boolean isStaffModeRevertBlocks() { return config.getBoolean("staffmode.revert_blocks_on_exit", true); }
    public int getStaffModeMaxTrackedBlocks() { return config.getInt("staffmode.max_tracked_blocks", 10000); }
    public String getStaffModeBypassPerm() { return config.getString("staffmode.bypass_permission", "vs.staffmode.bypass"); }

    // Districts
    public int getDistrictMinDistanceFromSpawn() { return config.getInt("districts.min_distance_from_spawn", 1500); }
    public int getDistrictMinDistanceBetween() { return config.getInt("districts.min_distance_between", 500); }

    // Restoration
    public int getRestoreNormalDelayMinutes() { return config.getInt("restoration.normal_delay_minutes", 10); }
    public int getRestoreExhaustedDelayMinutes() { return config.getInt("restoration.exhausted_delay_minutes", 30); }
    public int getRestoreDailyPoints() { return config.getInt("restoration.daily_repair_points", 500); }
    public int getRestoreDailyWage() { return config.getInt("restoration.daily_wage", 1000); }

    // Market
    public double getMarketTaxPercent() { return config.getDouble("market.tax_percent", 5.0); }
    public int getMarketListingDurationHours() { return config.getInt("market.default_listing_duration_hours", 48); }

    // Breach
    public int getBreachMaxDistance() { return config.getInt("breach.max_distance_blocks", 8); }
    public int getBreachEscapeCooldownSeconds() { return config.getInt("breach.escape_cooldown_seconds", 30); }
    public int getBreachTumblerSpeedTicks() { return config.getInt("breach.tumbler_speed_ticks", 6); }
    public int getBreachTumblerMaxAttempts() { return config.getInt("breach.tumbler_max_attempts", 3); }
    public int getBreachPressureDurationTicks() { return config.getInt("breach.pressure_duration_ticks", 80); }
    public int getBreachDialTimeTicks() { return config.getInt("breach.dial_time_ticks", 200); }
    public int getBreachDialMaxAttempts() { return config.getInt("breach.dial_max_attempts", 5); }

    // Resource Pack
    public String getResourcePackUrl() { return config.getString("resourcepack.url", ""); }
    public String getResourcePackHash() { return config.getString("resourcepack.hash", ""); }
    public boolean isResourcePackRequired() { return config.getBoolean("resourcepack.required", false); }

    // Updates
    public boolean areUpdatesEnabled() { return config.getBoolean("updates.enabled", true); }
    public String getUpdateGithubOwner() { return config.getString("updates.githubOwner", "mhinnen2310"); }
    public String getUpdateGithubRepo() { return config.getString("updates.githubRepo", "Vault-Survival"); }
    public String getUpdateAssetName() { return config.getString("updates.assetName", "VaultSurvival.jar"); }

    // Dialogs
    public boolean areDialogsEnabled() { return config.getBoolean("dialogs.enabled", true); }
    public boolean preferNativeDialogs() { return config.getBoolean("dialogs.preferNativeDialogs", true); }
    public boolean fallbackToInventoryGui() { return config.getBoolean("dialogs.fallbackToInventoryGui", true); }
    public boolean areQuickActionsEnabled() { return config.getBoolean("dialogs.quickActions.enabled", true); }
    public boolean shouldInstallQuickActionsDatapack() { return config.getBoolean("dialogs.quickActions.installDatapackToWorld", true); }

    // VS-WorldEdit
    public int getVweMaxBlocks() { return config.getInt("vsworldedit.maxBlocksPerOperation", 50000); }
    public int getVweBlocksPerTick() { return config.getInt("vsworldedit.blocksPerTick", 500); }
    public int getVweMaxUndo() { return config.getInt("vsworldedit.maxUndoOperations", 10); }
    public int getVweRequireConfirmAbove() { return config.getInt("vsworldedit.requireConfirmationAboveBlocks", 10000); }

    // Spawn City Regions (saved cuboids from VWE selections)
    public void setRegion(String type, String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        String path = "spawn.regions." + type.toLowerCase() + ".";
        config.set(path + "world", world);
        config.set(path + "x1", x1); config.set(path + "y1", y1); config.set(path + "z1", z1);
        config.set(path + "x2", x2); config.set(path + "y2", y2); config.set(path + "z2", z2);
        save();
    }
    public String getRegionWorld(String type) { return config.getString("spawn.regions." + type.toLowerCase() + ".world"); }
    public int getRegionX1(String type) { return config.getInt("spawn.regions." + type.toLowerCase() + ".x1"); }
    public int getRegionY1(String type) { return config.getInt("spawn.regions." + type.toLowerCase() + ".y1"); }
    public int getRegionZ1(String type) { return config.getInt("spawn.regions." + type.toLowerCase() + ".z1"); }
    public int getRegionX2(String type) { return config.getInt("spawn.regions." + type.toLowerCase() + ".x2"); }
    public int getRegionY2(String type) { return config.getInt("spawn.regions." + type.toLowerCase() + ".y2"); }
    public int getRegionZ2(String type) { return config.getInt("spawn.regions." + type.toLowerCase() + ".z2"); }
    public boolean hasRegion(String type) { return config.contains("spawn.regions." + type.toLowerCase() + ".world"); }
    public java.util.Set<String> getRegionTypes() {
        var sec = config.getConfigurationSection("spawn.regions");
        return sec != null ? sec.getKeys(false) : java.util.Collections.emptySet();
    }
}

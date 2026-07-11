package com.vaultsurvival.plugin.core;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
        byte[] defaultBytes = readDefaults(defaultConfigStream);
        if (!configFile.exists()) {
            configFile.getParentFile().mkdirs();
            try {
                if (defaultBytes.length > 0) {
                    java.nio.file.Files.write(configFile.toPath(), defaultBytes);
                    logger.info("Created default config.yml");
                } else {
                    configFile.createNewFile();
                }
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Failed to create config.yml", e);
            }
        }

        config = loadYaml(configFile);
        int storedConfigVersion = config.contains("configVersion") ? config.getInt("configVersion", 0) : 0;

        // Preserve customized values from the historical lowercase section.
        if (!config.isConfigurationSection("vsWorldEdit") && config.isConfigurationSection("vsworldedit")) {
            config.set("vsWorldEdit.safety.maxBlocksPerOperation",
                config.getInt("vsworldedit.maxBlocksPerOperation", 1000000));
            config.set("vsWorldEdit.safety.confirmationThresholdBlocks",
                config.getInt("vsworldedit.requireConfirmationAboveBlocks", 10000));
            config.set("vsWorldEdit.safety.requireConfirmationForAirOperations", true);
            config.set("vsWorldEdit.patterns.allowNumericAirAlias", true);
            config.set("vsWorldEdit.patterns.normalizePercentages", true);
            config.set("vsWorldEdit.patterns.maxPatternEntries",
                config.getInt("vsworldedit.patterns.maxPatternEntries",
                    config.getInt("vsworldedit.patterns.maxEntries", 32)));
            config.set("vsWorldEdit.patterns.defaultMode", "RANDOM");
            config.set("vsWorldEdit.patterns.allowLegacyCommaWeights", true);
        }

        migrateLegacyChunkSelectionSettings();

        if (defaultBytes.length > 0) {
            YamlConfiguration defaults = loadYaml(defaultBytes);
            int targetConfigVersion = defaults.getInt("configVersion", 1);
            if (storedConfigVersion < targetConfigVersion) {
                backupBeforeConfigRewrite(storedConfigVersion, targetConfigVersion);
                YamlConfiguration ordered = loadYaml(defaultBytes);
                for (String key : config.getKeys(true)) {
                    if (!config.isConfigurationSection(key)) ordered.set(key, config.get(key));
                }
                ordered.set("configVersion", targetConfigVersion);
                config = ordered;
                logger.info("Reordered config.yml to documented layout v" + targetConfigVersion
                    + " while preserving configured values.");
            }
            config.setDefaults(defaults);
            config.options().copyDefaults(true);
            String legacyChatFormat = config.getString("chat.format", "");
            if (legacyChatFormat.equals("%staff_marker%%district_role% &8┃ %district_label% &8┃ %player_name% &8> &f%message%")
                || legacyChatFormat.equals("%staff_marker%%district_role% &8â”ƒ %district_label% &8â”ƒ %player_name% &8> &f%message%")) {
                config.set("chat.format", "%district_label% &8| %district_role% &8| %player_name%%staff_marker% &8> &f%message%");
            }
            save();
        }
        applyRuntimeOverrides();
    }

    private byte[] readDefaults(InputStream stream) {
        if (stream == null) return new byte[0];
        try (InputStream input = stream) {
            return input.readAllBytes();
        } catch (IOException error) {
            logger.log(Level.WARNING, "Could not read bundled config defaults", error);
            return new byte[0];
        }
    }

    private YamlConfiguration loadYaml(File file) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        try {
            yaml.load(file);
        } catch (Exception error) {
            logger.log(Level.SEVERE, "Could not parse " + file.getName(), error);
        }
        return yaml;
    }

    private YamlConfiguration loadYaml(byte[] bytes) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        try {
            yaml.loadFromString(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception error) {
            logger.log(Level.SEVERE, "Could not parse bundled config.yml", error);
        }
        return yaml;
    }

    private void backupBeforeConfigRewrite(int fromVersion, int toVersion) {
        if (!configFile.exists()) return;
        File backup = new File(configFile.getParentFile(), "config.pre-v" + toVersion + ".bak.yml");
        if (backup.exists()) backup = new File(configFile.getParentFile(),
            "config.pre-v" + toVersion + "." + System.currentTimeMillis() + ".bak.yml");
        try {
            java.nio.file.Files.copy(configFile.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
            logger.info("Backed up config v" + fromVersion + " before layout migration: " + backup.getName());
        } catch (IOException error) {
            logger.log(Level.WARNING, "Could not create pre-migration config backup", error);
        }
    }

    /** Translate customized pre-block-selector limits before new defaults are merged. */
    private void migrateLegacyChunkSelectionSettings() {
        if (!config.contains("spawn.blockClaim.maxAreaBlocks") && config.contains("spawn.chunkClaim.maxChunks")) {
            config.set("spawn.blockClaim.maxAreaBlocks", Math.max(1L,
                config.getLong("spawn.chunkClaim.maxChunks", 256L) * 256L));
        }
        if (!config.contains("spawn.blockClaim.wandMaterial") && config.contains("spawn.chunkClaim.wandMaterial")) {
            config.set("spawn.blockClaim.wandMaterial", config.getString("spawn.chunkClaim.wandMaterial", "GOLDEN_AXE"));
        }
        config.set("spawn.chunkClaim", null);

        if (!config.contains("districts.selection.requiredAreaBlocks") && config.contains("districts.selection.requiredChunks")) {
            config.set("districts.selection.requiredAreaBlocks", Math.max(1L,
                config.getLong("districts.selection.requiredChunks", 15L) * 256L));
        }
        if (!config.contains("districts.selection.overlay.maxPreviewBlocks")
            && config.contains("districts.selection.overlay.maxPreviewChunks")) {
            config.set("districts.selection.overlay.maxPreviewBlocks", Math.max(1L,
                config.getLong("districts.selection.overlay.maxPreviewChunks", 64L) * 256L));
        }
        for (int level = 0; level <= 6; level++) {
            String legacy = "districts.selection.chunksByLevel." + level;
            String current = "districts.selection.areaBlocksByLevel." + level;
            if (!config.contains(current) && config.contains(legacy)) {
                config.set(current, Math.max(1L, config.getLong(legacy) * 256L));
            }
        }
        config.set("districts.selection.requiredChunks", null);
        config.set("districts.selection.chunksByLevel", null);
        config.set("districts.selection.overlay.maxPreviewChunks", null);
    }

    /**
     * A staff sandbox runs in its own Paper process. These in-memory overrides
     * remove production caps without writing sandbox values back to config.yml.
     */
    private void applyRuntimeOverrides() {
        if (!isStaffSandbox()) return;
        config.set("staffSandbox.enabled", true);
        config.set("server.name", "Vault Survival Staff Sandbox");
        config.set("database.file", "staff_sandbox.db");
        config.set("spawn.world", getStaffSandboxExpectedWorld());
        config.set("spawn.chunkClaim.maxChunks", 1000000);
        config.set("spawn.blockClaim.maxAreaBlocks", 1_000_000_000L);
        config.set("chat.channels.localRadius", 30000000);
        config.set("staffmode.allow_container_interact", true);
        config.set("staffmode.allow_item_drop", true);
        config.set("staffmode.allow_item_pickup", true);
        config.set("staffmode.revert_blocks_on_exit", false);
        config.set("staffmode.max_tracked_blocks", Integer.MAX_VALUE);
        config.set("security.anticheat.enabled", false);
        config.set("districts.min_distance_from_spawn", 0);
        config.set("districts.min_distance_between", 0);
        config.set("districts.selection.requiredChunks", 1);
        config.set("districts.selection.requiredAreaBlocks", 1L);
        config.set("districts.selection.timeoutMinutes", Integer.MAX_VALUE);
        for (int level = 0; level <= 6; level++) {
            config.set("districts.selection.chunksByLevel." + level, 1000000);
            config.set("districts.selection.areaBlocksByLevel." + level, 1_000_000_000L);
        }
        config.set("districts.marketZone.maxPercentOfDistrict", 1.0);
        config.set("districts.stationPlatform.maxPercentOfDistrict", 1.0);
        config.set("districts.laws.maxChangesPerDay", Integer.MAX_VALUE);
        config.set("districts.evidence.expireDays", 36500);
        config.set("districtDevelopment.scaling.enabled", false);
        config.set("districtDevelopment.scaling.dormantDistricts.enabled", false);
        config.set("restoration.normal_delay_minutes", 0);
        config.set("restoration.exhausted_delay_minutes", 0);
        config.set("restoration.daily_repair_points", Integer.MAX_VALUE);
        config.set("restoration.daily_wage", 0);
        config.set("merchant.max_active_orders", Integer.MAX_VALUE);
        config.set("merchant.default_expire_hours", 0);
        config.set("rail.defaultTicketPrice", 0);
        config.set("rail.defaultUpkeepCost", 0);
        config.set("rail.defaultKingdomTaxPercent", 0);
        config.set("rail.applicationFee", 0);
        config.set("rail.minPlatformRadius", 1);
        config.set("districtMarket.requireMarketZone", false);
        config.set("districtMarket.maxNpcPerMerchant", Integer.MAX_VALUE);
        config.set("districtMarket.maxNpcPerDistrict", Integer.MAX_VALUE);
        config.set("districtMarket.maxTaxPercent", 0);
        config.set("districtMarket.defaultTaxPercent", 0);
        config.set("market.tax_percent", 0.0);
        config.set("market.default_listing_duration_hours", 876000);
        config.set("market.max_listing_duration_hours", 876000);
        config.set("vaults.breach_cooldown_minutes", 0);
        config.set("vaults.max_breach_percent", 100.0);
        config.set("breach.max_distance_blocks", 30000000);
        config.set("breach.escape_cooldown_seconds", 0);
        config.set("breach.tumbler_speed_ticks", 1);
        config.set("breach.tumbler_max_attempts", 1000000);
        config.set("breach.dial_time_ticks", 72000);
        config.set("breach.dial_max_attempts", 1000000);
        config.set("vsworldedit.maxBlocksPerOperation", 100000000);
        config.set("vsworldedit.blocksPerTick", 10000);
        config.set("vsworldedit.maxUndoOperations", 1000000);
        config.set("vsworldedit.requireConfirmationAboveBlocks", Integer.MAX_VALUE);
        config.set("vsworldedit.patterns.maxEntries", Integer.MAX_VALUE);
        config.set("vsworldedit.patterns.maxWeight", Integer.MAX_VALUE);
        config.set("vsworldedit.patterns.maxPatternEntries", Integer.MAX_VALUE);
        config.set("vsworldedit.safety.maxBlocksPerOperation", Integer.MAX_VALUE);
        config.set("vsworldedit.safety.confirmationThresholdBlocks", Integer.MAX_VALUE);
        config.set("vsworldedit.safety.requireConfirmationForAirOperations", false);
        config.set("vsWorldEdit.patterns.maxPatternEntries", Integer.MAX_VALUE);
        config.set("vsWorldEdit.safety.maxBlocksPerOperation", Integer.MAX_VALUE);
        config.set("vsWorldEdit.safety.confirmationThresholdBlocks", Integer.MAX_VALUE);
        config.set("vsWorldEdit.safety.requireConfirmationForAirOperations", false);
        config.set("updates.enabled", false);
        logger.warning("STAFF SANDBOX MODE ACTIVE: isolated database and unlimited test overrides enabled.");
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

    // Dedicated staff sandbox (must run in a separate Paper instance)
    public boolean isStaffSandbox() {
        return Boolean.parseBoolean(System.getProperty("vaultsurvival.staffSandbox", "false"))
            || config.getBoolean("staffSandbox.enabled", false);
    }
    public String getStaffSandboxExpectedWorld() {
        return System.getProperty("vaultsurvival.staffSandbox.world",
            config.getString("staffSandbox.expectedWorld", "staff_test"));
    }
    public Set<UUID> getStaffSandboxAllowedUuids() {
        Set<UUID> allowed = new LinkedHashSet<>();
        for (String raw : config.getStringList("staffSandbox.allowedUuids")) addUuid(allowed, raw);
        String property = System.getProperty("vaultsurvival.staffSandbox.allowedUuids", "");
        for (String raw : property.split(",")) addUuid(allowed, raw);
        return Set.copyOf(allowed);
    }
    public boolean isStaffSandboxTransferEnabled() { return config.getBoolean("staffSandbox.transfer.enabled", true); }
    public String getStaffSandboxTestHost() {
        return System.getProperty("vaultsurvival.staffSandbox.testHost",
            config.getString("staffSandbox.transfer.testHost", "127.0.0.1"));
    }
    public int getStaffSandboxTestPort() {
        return Integer.getInteger("vaultsurvival.staffSandbox.testPort",
            config.getInt("staffSandbox.transfer.testPort", 25566));
    }
    public String getStaffSandboxProductionHost() {
        return System.getProperty("vaultsurvival.staffSandbox.productionHost",
            config.getString("staffSandbox.transfer.productionHost", "127.0.0.1"));
    }
    public int getStaffSandboxProductionPort() {
        return Integer.getInteger("vaultsurvival.staffSandbox.productionPort",
            config.getInt("staffSandbox.transfer.productionPort", 25565));
    }

    private void addUuid(Set<UUID> target, String raw) {
        if (raw == null || raw.isBlank()) return;
        try { target.add(UUID.fromString(raw.trim())); }
        catch (IllegalArgumentException ignored) { logger.warning("Ignoring invalid staff sandbox UUID: " + raw); }
    }
    
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
    public String getSpawnWelcomeMessage() { return config.getString("spawn.messages.welcome", "&eWelcome to Spawn City."); }
    public String getSpawnLeaveMessage() { return config.getString("spawn.messages.leave", "&7You are leaving Spawn City."); }
    public void setSpawnWelcomeMessage(String value) { config.set("spawn.messages.welcome", value); save(); }
    public void setSpawnLeaveMessage(String value) { config.set("spawn.messages.leave", value); save(); }
    public int getSpawnClaimMaxChunks() { return Math.max(1, config.getInt("spawn.chunkClaim.maxChunks", 256)); }
    public long getSpawnClaimMaxBlocks() {
        return Math.max(1L, config.getLong("spawn.blockClaim.maxAreaBlocks", (long) getSpawnClaimMaxChunks() * 256L));
    }
    public String getSpawnClaimWandMaterial() { return config.getString("spawn.blockClaim.wandMaterial",
        config.getString("spawn.chunkClaim.wandMaterial", "GOLDEN_AXE")); }

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
    public String getDefaultChatChannel() { return config.getString("chat.channels.default", "GLOBAL"); }
    public boolean shouldRememberLastChatChannel() { return config.getBoolean("chat.channels.rememberLastChannel", true); }
    public int getChatLocalRadius() { return config.getInt("chat.channels.localRadius", 100); }
    public boolean areChatAliasesEnabled() { return config.getBoolean("chat.channels.enableAliases", true); }
    public boolean allowGuestDistrictChat() { return config.getBoolean("chat.channels.allowGuestDistrictChat", false); }
    public boolean isChatSpyEnabled() { return config.getBoolean("chat.spy.enabled", true); }
    public boolean shouldAuditChatSpyToggle() { return config.getBoolean("chat.spy.auditSpyToggle", true); }

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
    public double getStaffSpeedMin() {
        return Math.max(0.0, Math.min(10.0, config.getDouble("staffmode.utilities.speed.min", 0.0)));
    }
    public double getStaffSpeedMax() {
        return Math.max(getStaffSpeedMin(), Math.min(10.0, config.getDouble("staffmode.utilities.speed.max", 10.0)));
    }
    public int getStaffWeatherMaxDurationSeconds() {
        return Math.max(1, Math.min(604800,
            config.getInt("staffmode.utilities.weather.maxDurationSeconds", 86400)));
    }
    public List<Integer> getStaffBreakerAllowedSizes() {
        List<Integer> configured = config.getIntegerList("staffmode.utilities.breaker.allowedSizes").stream()
            .filter(size -> size >= 3 && size <= 9)
            .distinct()
            .sorted()
            .toList();
        return configured.isEmpty() ? List.of(3, 4, 5, 6, 7, 8, 9) : configured;
    }
    public boolean isStaffBreakerSkipContainers() {
        return config.getBoolean("staffmode.utilities.breaker.skipContainers", true);
    }
    public boolean isStaffBreakerSameMaterialOnly() {
        return config.getBoolean("staffmode.utilities.breaker.sameMaterialOnly", false);
    }
    public boolean isStaffBreakerApplyPhysics() {
        return config.getBoolean("staffmode.utilities.breaker.applyPhysics", false);
    }
    public Set<Material> getStaffBreakerProtectedMaterials() {
        Set<Material> materials = new LinkedHashSet<>();
        for (String name : config.getStringList("staffmode.utilities.breaker.protectedMaterials")) {
            Material material = Material.matchMaterial(name);
            if (material != null) materials.add(material);
        }
        if (materials.isEmpty()) {
            materials.addAll(Set.of(Material.BEDROCK, Material.BARRIER, Material.STRUCTURE_BLOCK,
                Material.JIGSAW, Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK,
                Material.REPEATING_COMMAND_BLOCK, Material.END_PORTAL, Material.END_PORTAL_FRAME,
                Material.END_GATEWAY, Material.NETHER_PORTAL, Material.REINFORCED_DEEPSLATE));
        }
        return Set.copyOf(materials);
    }
    public List<String> getStaffModeBuildPermissionNodes() {
        List<String> nodes = config.getStringList("staffmode.buildPermissions.temporaryPermissionNodes").stream()
            .map(String::trim)
            .filter(node -> !node.isEmpty() && !node.equalsIgnoreCase("vs.*") && !node.equals("*"))
            .distinct()
            .toList();
        return nodes.isEmpty() ? List.of("worldguard.region.bypass.%world%") : nodes;
    }

    // Districts
    public int getDistrictMinDistanceFromSpawn() { return config.getInt("districts.min_distance_from_spawn", 500); }
    public int getDistrictMinDistanceBetween() { return config.getInt("districts.min_distance_between", 350); }
    public int getDistrictInitialClaimChunks() { return Math.max(1, config.getInt("districts.selection.requiredChunks", 15)); }
    public int getDistrictClaimChunksAtLevel(int level) {
        int highestKnown = Math.max(0, Math.min(6, level));
        return Math.max(getDistrictInitialClaimChunks(), config.getInt("districts.selection.chunksByLevel." + highestKnown, getDistrictInitialClaimChunks()));
    }
    public long getDistrictInitialClaimBlocks() {
        return Math.max(1L, config.getLong("districts.selection.requiredAreaBlocks",
            (long) getDistrictInitialClaimChunks() * 256L));
    }
    public long getDistrictClaimBlocksAtLevel(int level) {
        int highestKnown = Math.max(0, Math.min(6, level));
        return Math.max(getDistrictInitialClaimBlocks(), config.getLong(
            "districts.selection.areaBlocksByLevel." + highestKnown,
            (long) getDistrictClaimChunksAtLevel(highestKnown) * 256L));
    }
    public boolean isDistrictSelectionOverlayEnabled() { return config.getBoolean("districts.selection.overlay.enabled", true); }
    public int getDistrictSelectionTimeoutMinutes() { return Math.max(1, config.getInt("districts.selection.timeoutMinutes", 20)); }
    public int getDistrictMaxLawChangesPerDay() { return Math.max(1, config.getInt("districtLaws.maxPendingChanges", config.getInt("districts.laws.maxChangesPerDay", 5))); }
    public int getEvidenceExpireDays() { return config.getInt("districts.evidence.expireDays", 14); }

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
    public int getVweMaxBlocks() { return config.getInt("vsWorldEdit.safety.maxBlocksPerOperation",
        config.getInt("vsworldedit.safety.maxBlocksPerOperation",
            config.getInt("vsworldedit.maxBlocksPerOperation", 1000000))); }
    public int getVweBlocksPerTick() { return config.getInt("vsworldedit.blocksPerTick", 500); }
    public int getVweMaxUndo() { return config.getInt("vsworldedit.maxUndoOperations", 10); }
    public int getVweRequireConfirmAbove() { return config.getInt("vsWorldEdit.safety.confirmationThresholdBlocks",
        config.getInt("vsworldedit.safety.confirmationThresholdBlocks",
            config.getInt("vsworldedit.requireConfirmationAboveBlocks", 10000))); }

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

package com.vaultsurvival.plugin.spawncity;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.ConfigManager;
import com.vaultsurvival.plugin.core.MessageFormatter;
import com.vaultsurvival.plugin.vsworldedit.VSWorldEditData;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Implementation of SpawnCityService.
 * Stores spawn data and region cuboids in config.yml for persistence.
 */
public class SpawnCityServiceImpl implements SpawnCityService {

    private final VaultSurvivalPlugin plugin;
    private final ConfigManager config;
    private final MessageFormatter fmt;

    private static final List<String> VALID_REGIONS = List.of("capital", "auction_hall", "mint");

    public SpawnCityServiceImpl(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.fmt = plugin.getMessageFormatter();
    }

    @Override public String getCityName() { return config.getSpawnCityName(); }
    @Override public void setCityName(String name) { config.setSpawnCityName(name); }

    @Override
    public Location getSpawnLocation() {
        World world = Bukkit.getWorld(config.getSpawnWorld());
        if (world == null) return null;
        return new Location(world, config.getSpawnX(), config.getSpawnY(), config.getSpawnZ(),
            config.getSpawnYaw(), config.getSpawnPitch());
    }

    @Override
    public void setSpawnLocation(Location loc) {
        config.setSpawnLocation(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(),
            loc.getYaw(), loc.getPitch());
    }

    @Override
    public void teleportToSpawn(Player player) {
        Location spawn = getSpawnLocation();
        if (spawn == null) {
            player.sendMessage(fmt.error("Spawn location not set. Use /spawncity setspawn first."));
            return;
        }
        player.teleport(spawn);
        player.sendMessage(fmt.success("Teleported to " + getCityName() + "."));
    }

    // ========================================================================
    // Regions
    // ========================================================================

    @Override
    public void setCapitalRegion(VSWorldEditData.Selection sel) {
        saveRegion("capital", sel);
    }

    @Override
    public void setAuctionHallRegion(VSWorldEditData.Selection sel) {
        saveRegion("auction_hall", sel);
    }

    @Override
    public void setMintRegion(VSWorldEditData.Selection sel) {
        saveRegion("mint", sel);
    }

    private void saveRegion(String type, VSWorldEditData.Selection sel) {
        config.setRegion(type, sel.getWorldName(),
            sel.getX1(), sel.getY1(), sel.getZ1(),
            sel.getX2(), sel.getY2(), sel.getZ2());
    }

    @Override
    public VSWorldEditData.Selection getRegion(String type) {
        if (!config.hasRegion(type)) return null;
        World world = Bukkit.getWorld(config.getRegionWorld(type));
        if (world == null) return null;
        Location pos1 = new Location(world, config.getRegionX1(type), config.getRegionY1(type), config.getRegionZ1(type));
        Location pos2 = new Location(world, config.getRegionX2(type), config.getRegionY2(type), config.getRegionZ2(type));
        return new VSWorldEditData.Selection(null, pos1, pos2);
    }

    @Override
    public List<String> getRegionTypes() {
        return new ArrayList<>(config.getRegionTypes());
    }

    @Override
    public boolean hasRegion(String type) {
        return config.hasRegion(type.toLowerCase());
    }
}

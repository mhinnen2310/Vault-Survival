package com.vaultsurvival.plugin.staffmode;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.Module;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VS-Staffmode module: Staff mode with inventory separation,
 * block tracking/revert, chest protection, and owner bypass.
 */
public class StaffmodeModule extends Module {

    private final Map<UUID, StaffmodeData> staffData = new ConcurrentHashMap<>();
    private StaffmodeListener listener;

    public StaffmodeModule(VaultSurvivalPlugin plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "VS-Staffmode";
    }

    @Override
    public String[] getDependencies() {
        return new String[] { "VS-Access" };
    }

    @Override
    public void onLoad() {
        plugin.getLogger().info("Staff mode module loaded");
    }

    @Override
    public void onEnable() {
        listener = new StaffmodeListener(plugin, staffData);
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);

        var cmd = new StaffmodeCommand(plugin, staffData, listener);
        plugin.getCommand("staffmode").setExecutor(cmd);
        plugin.getCommand("staffmode").setTabCompleter(cmd);
    }

    @Override
    public void onDisable() {
        // Force-disable staff mode for all online players
        for (var entry : staffData.entrySet()) {
            var player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null && entry.getValue().isStaffModeActive()) {
                listener.removeVisibilityEffects(player);
                player.getInventory().clear();
                if (entry.getValue().getGameplayInventory() != null) {
                    player.getInventory().setContents(entry.getValue().getGameplayInventory());
                }
                if (entry.getValue().getGameplayArmor() != null) {
                    player.getInventory().setArmorContents(entry.getValue().getGameplayArmor());
                }
                player.displayName(null);
                player.playerListName(null);
            }
        }
        staffData.clear();
    }

    public Map<UUID, StaffmodeData> getStaffData() {
        return staffData;
    }
}

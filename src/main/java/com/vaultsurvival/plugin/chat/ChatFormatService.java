package com.vaultsurvival.plugin.chat;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.access.AccessService;
import com.vaultsurvival.plugin.core.ConfigManager;
import com.vaultsurvival.plugin.districts.DistrictData;
import com.vaultsurvival.plugin.districts.DistrictService;
import org.bukkit.entity.Player;

/**
 * Builds the Vault Survival chat prefix from access rank and district data.
 */
public class ChatFormatService {

    private final VaultSurvivalPlugin plugin;
    private final ConfigManager config;

    public ChatFormatService(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
    }

    public String getRankLabel(Player player) {
        String groupName = "default";
        try {
            AccessService accessService = plugin.getServiceRegistry().get(AccessService.class);
            String primaryGroup = accessService.getPrimaryGroup(player.getUniqueId());
            if (primaryGroup != null && !primaryGroup.isBlank()) {
                groupName = primaryGroup;
            }
        } catch (RuntimeException ignored) {
            groupName = "default";
        }
        return config.getChatRankLabel(groupName);
    }

    public String getDistrictLabel(Player player) {
        String noDistrict = config.getNoDistrictLabel();
        if (!config.isDistrictChatEnabled()) {
            return noDistrict;
        }

        try {
            DistrictService districtService = plugin.getServiceRegistry().get(DistrictService.class);
            DistrictData.District district = districtService.getPlayerDistrict(player.getUniqueId());
            if (district == null || district.getName() == null || district.getName().isBlank()) {
                return noDistrict;
            }
            return config.getDistrictLabelFormat().replace("%district%", district.getName());
        } catch (RuntimeException ignored) {
            return noDistrict;
        }
    }

    public String getDistrictRoleLabel(Player player) {
        try {
            DistrictService districts = plugin.getServiceRegistry().get(DistrictService.class);
            DistrictData.District district = districts.getPlayerDistrict(player.getUniqueId());
            if (district == null) return "&7VISITOR";
            return "&b" + districts.getHighestDistrictRole(player.getUniqueId(), district).name();
        } catch (RuntimeException ignored) { return "&7VISITOR"; }
    }

    private String getStaffMarker(Player player) {
        try {
            AccessService access = plugin.getServiceRegistry().get(AccessService.class);
            return access.isStaff(player.getUniqueId()) ? "&c* &r" : "";
        } catch (RuntimeException ignored) { return ""; }
    }

    public String formatChat(Player player, String message) {
        return config.getChatFormat()
            .replace("%staff_marker%", getStaffMarker(player))
            .replace("%district_role%", getDistrictRoleLabel(player))
            .replace("%rank_label%", getRankLabel(player))
            .replace("%district_label%", getDistrictLabel(player))
            .replace("%player_name%", config.getChatPlayerNameColor() + player.getName())
            .replace("%message%", message);
    }
}

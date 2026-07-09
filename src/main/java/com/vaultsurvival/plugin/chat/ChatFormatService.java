package com.vaultsurvival.plugin.chat;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.access.AccessService;
import com.vaultsurvival.plugin.core.ConfigManager;
import com.vaultsurvival.plugin.districts.DistrictData;
import com.vaultsurvival.plugin.districts.DistrictService;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;

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
            return config.getDistrictLabelFormat().replace("%district%", districtService.getDistrictChatPrefix(district));
        } catch (RuntimeException ignored) {
            return noDistrict;
        }
    }

    public String getDistrictRoleLabel(Player player) {
        try {
            DistrictService districts = plugin.getServiceRegistry().get(DistrictService.class);
            DistrictData.District district = districts.getPlayerDistrict(player.getUniqueId());
            if (district == null) return "&7VISITOR";
            DistrictData.DistrictRole role = districts.getHighestDistrictRole(player.getUniqueId(), district);
            return districts.getDistrictRoleColor(district, role) + role.name();
        } catch (RuntimeException ignored) { return "&7VISITOR"; }
    }

    public String getStaffMarker(Player player) {
        try {
            AccessService access = plugin.getServiceRegistry().get(AccessService.class);
            return access.isStaff(player.getUniqueId()) ? "&c*" : "";
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

    /** Builds a chat component with a hover card only on the username. */
    public Component formatChatComponent(Player player, String message) {
        String token = "__VS_PLAYER__";
        String template = config.getChatFormat()
            .replace("%staff_marker%", getStaffMarker(player))
            .replace("%district_role%", getDistrictRoleLabel(player))
            .replace("%rank_label%", getRankLabel(player))
            .replace("%district_label%", getDistrictLabel(player))
            .replace("%player_name%", token)
            .replace("%message%", message);
        int index = template.indexOf(token);
        if (index < 0) return plugin.getMessageFormatter().deserialize(template);
        Component before = plugin.getMessageFormatter().deserialize(template.substring(0, index));
        Component username = plugin.getMessageFormatter().deserialize(config.getChatPlayerNameColor() + player.getName())
            .hoverEvent(HoverEvent.showText(plugin.getMessageFormatter().deserialize(hoverText(player))));
        Component after = plugin.getMessageFormatter().deserialize(template.substring(index + token.length()));
        return before.append(username).append(after);
    }

    private String hoverText(Player player) {
        String districtName = "None";
        String role = "VISITOR";
        try {
            DistrictService districts = plugin.getServiceRegistry().get(DistrictService.class);
            DistrictData.District district = districts.getPlayerDistrict(player.getUniqueId());
            if (district != null) {
                districtName = district.getName();
                role = districts.getHighestDistrictRole(player.getUniqueId(), district).name();
            }
        } catch (RuntimeException ignored) { }
        return "&f" + player.getName() + "\n&7Rank: " + getRankLabel(player)
            + "\n&7District: &e" + districtName + "\n&7District role: &f" + role
            + (getStaffMarker(player).isEmpty() ? "" : "\n&cStaff");
    }
}

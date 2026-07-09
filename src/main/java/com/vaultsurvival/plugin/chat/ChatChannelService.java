package com.vaultsurvival.plugin.chat;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.ConfigManager;
import com.vaultsurvival.plugin.core.MessageFormatter;
import com.vaultsurvival.plugin.districts.DistrictData;
import com.vaultsurvival.plugin.districts.DistrictService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ChatChannelService {

    private static final Set<DistrictData.DistrictRole> POLICE_ROLES = EnumSet.of(
        DistrictData.DistrictRole.POLICE,
        DistrictData.DistrictRole.WARDEN,
        DistrictData.DistrictRole.MAYOR,
        DistrictData.DistrictRole.CO_MAYOR
    );
    private static final Set<DistrictData.DistrictRole> MERCHANT_ROLES = EnumSet.of(
        DistrictData.DistrictRole.MERCHANT,
        DistrictData.DistrictRole.TREASURER,
        DistrictData.DistrictRole.MAYOR,
        DistrictData.DistrictRole.CO_MAYOR
    );

    private final VaultSurvivalPlugin plugin;
    private final ConfigManager config;
    private final MessageFormatter fmt;
    private final ChatFormatService formatService;
    private final Map<UUID, ChatChannel> activeChannels = new ConcurrentHashMap<>();

    public ChatChannelService(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.fmt = plugin.getMessageFormatter();
        this.formatService = new ChatFormatService(plugin);
    }

    public ChatChannel getActiveChannel(Player player) {
        return activeChannels.getOrDefault(player.getUniqueId(), getDefaultChannel());
    }

    public boolean setActiveChannel(Player player, ChatChannel channel) {
        String denial = getAccessDenial(player, channel);
        if (denial != null) {
            player.sendMessage(fmt.error(denial));
            return false;
        }
        if (config.shouldRememberLastChatChannel()) {
            activeChannels.put(player.getUniqueId(), channel);
        }
        if (channel == ChatChannel.STAFF && config.shouldAuditChatSpyToggle()) {
            plugin.getAuditLogger().logAdminAction(player.getUniqueId(), player.getName(),
                "CHAT_CHANNEL_SWITCH", "SELF", "channel=STAFF");
        }
        player.sendMessage(fmt.success("Active chat channel: " + channel.displayName()));
        return true;
    }

    public void sendActive(Player player, String message) {
        send(player, getActiveChannel(player), message);
    }

    public void send(Player player, ChatChannel channel, String message) {
        if (message == null || message.isBlank()) {
            player.sendMessage(fmt.error("Message cannot be empty."));
            return;
        }
        String denial = getAccessDenial(player, channel);
        if (denial != null) {
            player.sendMessage(fmt.error(denial));
            return;
        }

        Set<Player> recipients = recipientsFor(player, channel);
        if (recipients.isEmpty()) {
            player.sendMessage(fmt.warn("No players can hear this channel right now."));
            return;
        }

        String formatted = formatService.formatChat(player, channel.prefix() + " " + message);
        Component component = fmt.deserialize(formatted);
        for (Player recipient : recipients) {
            recipient.sendMessage(component);
        }
    }

    public Set<Player> recipientsFor(Player sender, ChatChannel channel) {
        Set<Player> recipients = ConcurrentHashMap.newKeySet();
        switch (channel) {
            case GLOBAL, HELP -> recipients.addAll(Bukkit.getOnlinePlayers());
            case LOCAL -> {
                int radius = Math.max(1, config.getChatLocalRadius());
                double maxDistanceSquared = radius * radius;
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getWorld().equals(sender.getWorld())
                        && player.getLocation().distanceSquared(sender.getLocation()) <= maxDistanceSquared) {
                        recipients.add(player);
                    }
                }
            }
            case DISTRICT -> {
                DistrictData.District district = getDistrict(sender);
                if (district != null) {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        if (isDistrictRecipient(player, district)) {
                            recipients.add(player);
                        }
                    }
                }
            }
            case POLICE -> addRoleRecipients(sender, recipients, POLICE_ROLES);
            case MERCHANT -> addRoleRecipients(sender, recipients, MERCHANT_ROLES);
            case ALLY -> {
                playerPlaceholder(sender, "Ally diplomacy is not active yet; message stayed in your district.");
                DistrictData.District district = getDistrict(sender);
                if (district != null) {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        if (isDistrictRecipient(player, district)) {
                            recipients.add(player);
                        }
                    }
                }
            }
            case STAFF -> {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (hasStaffChat(player)) {
                        recipients.add(player);
                    }
                }
            }
        }
        recipients.add(sender);
        if (config.isChatSpyEnabled() && channel != ChatChannel.STAFF) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (hasStaffSpy(player)) {
                    recipients.add(player);
                    if (config.shouldAuditChatSpyToggle()) {
                        plugin.getAuditLogger().logAdminAction(player.getUniqueId(), player.getName(),
                            "CHAT_SPY_VIEW", sender.getName(), "channel=" + channel.name());
                    }
                }
            }
        }
        return recipients;
    }

    public String getAccessDenial(Player player, ChatChannel channel) {
        if (channel == null) {
            return "Unknown chat channel.";
        }
        if (!player.hasPermission("vs.chat")) {
            return "You do not have permission to chat.";
        }
        return switch (channel) {
            case DISTRICT -> canUseDistrict(player) ? null : "District chat requires district membership.";
            case ALLY -> canUseDistrict(player) ? null : "Ally chat requires district membership. Diplomacy is a placeholder.";
            case POLICE -> hasAnyRole(player, POLICE_ROLES) ? null : "Police chat requires POLICE, WARDEN, CO_MAYOR, or MAYOR.";
            case MERCHANT -> hasAnyRole(player, MERCHANT_ROLES) ? null : "Merchant chat requires MERCHANT, TREASURER, CO_MAYOR, or MAYOR.";
            case STAFF -> hasStaffChat(player) ? null : "Staff chat requires vs.staff.chat.";
            default -> null;
        };
    }

    public ChatChannel getDefaultChannel() {
        ChatChannel channel = ChatChannel.from(config.getDefaultChatChannel());
        return channel != null ? channel : ChatChannel.GLOBAL;
    }

    public String preview(Player player, ChatChannel channel, String message) {
        return formatService.formatChat(player, channel.prefix() + " " + message);
    }

    private void addRoleRecipients(Player sender, Set<Player> recipients, Set<DistrictData.DistrictRole> roles) {
        DistrictData.District district = getDistrict(sender);
        if (district == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isDistrictRecipient(player, district) && hasAnyRole(player, roles)) {
                recipients.add(player);
            }
        }
    }

    private boolean isDistrictRecipient(Player player, DistrictData.District district) {
        DistrictData.District other = getDistrict(player);
        return other != null && other.getId() == district.getId() && canUseDistrict(player);
    }

    private boolean canUseDistrict(Player player) {
        DistrictData.District district = getDistrict(player);
        if (district == null) {
            return false;
        }
        if (config.allowGuestDistrictChat()) {
            return !district.getHighestRole(player.getUniqueId()).equals(DistrictData.DistrictRole.VISITOR);
        }
        DistrictData.DistrictRole highestRole = district.getHighestRole(player.getUniqueId());
        return highestRole != DistrictData.DistrictRole.VISITOR && highestRole != DistrictData.DistrictRole.GUEST;
    }

    private boolean hasAnyRole(Player player, Set<DistrictData.DistrictRole> roles) {
        DistrictData.District district = getDistrict(player);
        if (district == null) {
            return false;
        }
        for (DistrictData.DistrictRole role : roles) {
            if (district.hasRole(player.getUniqueId(), role)) {
                return true;
            }
        }
        return false;
    }

    private DistrictData.District getDistrict(Player player) {
        try {
            DistrictService districtService = plugin.getServiceRegistry().get(DistrictService.class);
            return districtService.getPlayerDistrict(player.getUniqueId());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private boolean hasStaffChat(Player player) {
        return player.hasPermission("vs.staff.chat") || player.hasPermission("vs.admin");
    }

    private boolean hasStaffSpy(Player player) {
        return player.hasPermission("vs.staff.spy") || player.hasPermission("vs.admin");
    }

    private void playerPlaceholder(Player player, String message) {
        player.sendMessage(fmt.warn(message));
    }

    public static String channelList() {
        return java.util.Arrays.stream(ChatChannel.values())
            .map(channel -> channel.id().toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.joining(", "));
    }
}

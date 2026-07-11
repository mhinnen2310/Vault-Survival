package com.vaultsurvival.plugin.staffmode;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.access.AccessService;
import com.vaultsurvival.plugin.core.ConfigManager;
import com.vaultsurvival.plugin.core.MessageFormatter;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Staff mode command.
 * /staffmode - Toggle staff mode on/off
 * /staffmode * - Toggle owner bypass mode (overrides all restrictions)
 * /staffmode * <player> - Toggle bypass for another staff member (owner only)
 * /staffmode build <player> <on|off> - Grant session-only persistent build access (owner only)
 */
public class StaffmodeCommand implements CommandExecutor, TabCompleter {

    private final VaultSurvivalPlugin plugin;
    private final Map<UUID, StaffmodeData> staffData;
    private final ConfigManager config;
    private final MessageFormatter fmt;
    private final StaffmodeListener listener;

    public StaffmodeCommand(VaultSurvivalPlugin plugin, Map<UUID, StaffmodeData> staffData,
                            StaffmodeListener listener) {
        this.plugin = plugin;
        this.staffData = staffData;
        this.config = plugin.getConfigManager();
        this.fmt = plugin.getMessageFormatter();
        this.listener = listener;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(fmt.error("Only players can use staff mode."));
            return true;
        }

        if (!hasVsPermission(player, "vs.staffmode.use")) {
            player.sendMessage(fmt.permissionDenied());
            plugin.getAuditLogger().log(player.getUniqueId(), player.getName(), "STAFFMODE_DENIED", "PLAYER",
                player.getUniqueId().toString(), "missing=vs.staffmode.use");
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("build")) {
            return handleBuildPermission(player, args);
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("test")) {
            return transferToSandbox(player);
        }
        if (args.length >= 1 && (args[0].equalsIgnoreCase("return") || args[0].equalsIgnoreCase("back"))) {
            return transferToProduction(player);
        }

        // /staffmode * [player] - bypass mode
        if (args.length >= 1 && args[0].equals("*")) {
            return handleBypass(player, args);
        }

        // /staffmode - toggle
        return toggleStaffMode(player);
    }

    private boolean toggleStaffMode(Player player) {
        UUID uuid = player.getUniqueId();
        StaffmodeData data = staffData.computeIfAbsent(uuid, k -> new StaffmodeData(uuid));

        if (data.isStaffModeActive()) {
            // EXIT staff mode
            disableStaffMode(player, data);
        } else {
            // ENTER staff mode
            enableStaffMode(player, data);
        }

        return true;
    }

    private void enableStaffMode(Player player, StaffmodeData data) {
        data.setStaffModeActive(true);
        data.setBreakerSize(0);
        listener.setBuildPermission(player, data, false);
        data.setGameplayLocation(player.getLocation().clone());

        // Store gameplay inventory if config says to separate
        if (config.isStaffModeSeparateInventories()) {
            ItemStack[] gameplayInv = player.getInventory().getContents().clone();
            ItemStack[] gameplayArmor = player.getInventory().getArmorContents().clone();
            data.setGameplayInventory(gameplayInv);
            data.setGameplayArmor(gameplayArmor);
            player.getInventory().clear();
            player.getInventory().setArmorContents(null);
        }

        // Apply visibility effects
        listener.applyVisibilityEffects(player, data);

        // Set game mode from config for staff tools
        String modeStr = config.getConfig().getString("staffmode.gamemode", "CREATIVE");
        try {
            player.setGameMode(GameMode.valueOf(modeStr.toUpperCase()));
        } catch (IllegalArgumentException e) {
            player.setGameMode(GameMode.CREATIVE);
        }

        // Staff prefix in name
        String prefix = config.getStaffModePrefix();
        player.displayName(fmt.deserialize(prefix + " &r" + player.getName()));
        player.playerListName(fmt.deserialize(prefix + " &r" + player.getName()));

        player.sendMessage(fmt.success("Staff mode enabled. Inventory separated."));
        player.sendMessage(fmt.info("Your gameplay inventory is stored safely."));
        player.sendMessage(fmt.info("Use &e/staffmode *&7 for testing bypass."));
        player.sendMessage(fmt.info("Use &e/staffmode test&7 to enter the isolated staff test world."));
        plugin.getAuditLogger().log(player.getUniqueId(), player.getName(), "STAFFMODE_ENABLE", "PLAYER",
            player.getUniqueId().toString(), "gamemode=" + player.getGameMode());
    }

    private void disableStaffMode(Player player, StaffmodeData data) {
        // Revert blocks if configured
        if (config.isStaffModeRevertBlocks() && !data.isBypassMode()) {
            listener.revertBlocks(player, data);
        }

        // Remove visibility effects
        listener.removeVisibilityEffects(player);

        // Restore gameplay inventory
        if (config.isStaffModeSeparateInventories()) {
            player.getInventory().clear();
            if (data.getGameplayInventory() != null) {
                player.getInventory().setContents(data.getGameplayInventory());
            }
            if (data.getGameplayArmor() != null) {
                player.getInventory().setArmorContents(data.getGameplayArmor());
            }
        }

        // Reset name display
        player.displayName(null);
        player.playerListName(null);

        listener.setBuildPermission(player, data, false);
        data.setStaffModeActive(false);
        data.setBypassMode(false);
        data.setBreakerSize(0);
        data.clearBlockChanges();

        player.setGameMode(GameMode.SURVIVAL);
        if (data.getGameplayLocation() != null) player.teleport(data.getGameplayLocation());
        data.setGameplayLocation(null);
        player.sendMessage(fmt.success("Staff mode disabled. Gameplay inventory restored."));
        plugin.getAuditLogger().log(player.getUniqueId(), player.getName(), "STAFFMODE_DISABLE", "PLAYER",
            player.getUniqueId().toString(), "inventoryRestored=true");
    }

    private boolean handleBypass(Player player, String[] args) {
        // /staffmode * [player]
        Player target = player;

        if (args.length >= 2) {
            // Only owner can grant bypass to others
            if (!hasVsPermission(player, "vs.staffmode.bypass.grant")) {
                player.sendMessage(fmt.error("Only the owner can grant bypass to other staff."));
                return true;
            }

            target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                player.sendMessage(fmt.playerNotFound(args[1]));
                return true;
            }
        }

        // Only owner or players with bypass grant can use bypass
        if (!hasVsPermission(player, "vs.staffmode.bypass") && !hasVsPermission(player, "vs.staffmode.bypass.grant")) {
            player.sendMessage(fmt.error("You don't have permission for bypass mode."));
            return true;
        }

        UUID targetUuid = target.getUniqueId();
        StaffmodeData data = staffData.get(targetUuid);
        if (data == null || !data.isStaffModeActive()) {
            player.sendMessage(fmt.error(target == player ? "You must be in staff mode first." :
                "That player is not in staff mode."));
            return true;
        }

        data.setBypassMode(!data.isBypassMode());

        if (data.isBypassMode()) {
            if (target != player) {
                player.sendMessage(fmt.success("Bypass mode enabled for " + target.getName() + "."));
            }
            target.sendMessage(fmt.success("Bypass mode ENABLED. All staffmode restrictions lifted."));
        } else {
            if (target != player) {
                player.sendMessage(fmt.success("Bypass mode disabled for " + target.getName() + "."));
            }
            target.sendMessage(fmt.success("Bypass mode DISABLED. Restrictions active."));
        }

        plugin.getAuditLogger().log(player.getUniqueId(), player.getName(), "STAFFMODE_BYPASS_"
            + (data.isBypassMode() ? "ENABLE" : "DISABLE"), "PLAYER", targetUuid.toString(),
            "target=" + target.getName());

        return true;
    }

    private boolean handleBuildPermission(Player owner, String[] args) {
        if (!hasVsPermission(owner, "vs.staffmode.build.grant") || !isOwnerRank(owner)) {
            owner.sendMessage(fmt.error("Only the owner can grant persistent staffmode build access."));
            plugin.getAuditLogger().log(owner.getUniqueId(), owner.getName(), "STAFF_BUILD_GRANT_DENIED", "PLAYER",
                owner.getUniqueId().toString(), "requiredGroup=owner requiredPermission=vs.staffmode.build.grant");
            return true;
        }
        if (args.length < 2) {
            owner.sendMessage(fmt.info("Usage: &e/staffmode build <player> <on|off>&7."));
            owner.sendMessage(fmt.info("For yourself: &e/staffmode build <on|off>&7."));
            return true;
        }

        Player target;
        String stateToken;
        if (isToggleToken(args[1])) {
            target = owner;
            stateToken = args[1];
        } else {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                owner.sendMessage(fmt.playerNotFound(args[1]));
                return true;
            }
            stateToken = args.length >= 3 ? args[2] : "toggle";
        }
        if (!isToggleToken(stateToken)) {
            owner.sendMessage(fmt.error("Choose on, off, or toggle."));
            return true;
        }

        StaffmodeData targetData = staffData.get(target.getUniqueId());
        if (targetData == null || !targetData.isStaffModeActive()) {
            owner.sendMessage(fmt.error(target.getName() + " must be in staffmode first."));
            return true;
        }

        boolean enabled = switch (stateToken.toLowerCase(Locale.ROOT)) {
            case "on", "enable", "enabled", "true" -> true;
            case "off", "disable", "disabled", "false" -> false;
            default -> !targetData.isBuildPermissionEnabled();
        };
        listener.setBuildPermission(target, targetData, enabled);

        String status = enabled ? "enabled" : "disabled";
        owner.sendMessage(fmt.success("Persistent staffmode build access " + status + " for " + target.getName() + "."));
        if (target != owner) {
            target.sendMessage(enabled
                ? fmt.warn("The owner enabled persistent build access. Your block changes will NOT revert on exit.")
                : fmt.info("The owner disabled persistent build access. New changes are temporary again."));
        }
        plugin.getAuditLogger().log(owner.getUniqueId(), owner.getName(), "STAFF_BUILD_"
            + (enabled ? "GRANT" : "REVOKE"), "PLAYER", target.getUniqueId().toString(),
            "target=" + target.getName() + " staffmode=true permissionNodes="
                + config.getStaffModeBuildPermissionNodes().size());
        return true;
    }

    private boolean isToggleToken(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "on", "off", "toggle", "enable", "disable", "enabled", "disabled", "true", "false" -> true;
            default -> false;
        };
    }

    private boolean isOwnerRank(Player player) {
        if (config.isStaffSandbox() && config.getStaffSandboxAllowedUuids().contains(player.getUniqueId())) {
            return true;
        }
        try {
            AccessService accessService = plugin.getServiceRegistry().get(AccessService.class);
            return Arrays.stream(accessService.getPlayerGroups(player.getUniqueId()))
                .anyMatch(group -> group.equalsIgnoreCase("owner"));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean transferToSandbox(Player player) {
        if (config.isStaffSandbox()) {
            player.sendMessage(fmt.info("You are already in the isolated staff sandbox. Use &e/staffmode return&7 to go back."));
            return true;
        }
        StaffmodeData data = staffData.get(player.getUniqueId());
        if (data == null || !data.isStaffModeActive()) {
            player.sendMessage(fmt.error("Enable staffmode before entering the test world."));
            return true;
        }
        if (!config.isStaffSandboxTransferEnabled()) {
            player.sendMessage(fmt.error("Staff sandbox transfer is disabled in config.yml."));
            return true;
        }
        plugin.getAuditLogger().log(player.getUniqueId(), player.getName(), "STAFF_SANDBOX_ENTER", "PLAYER",
            player.getUniqueId().toString(), "host=" + config.getStaffSandboxTestHost() + " port=" + config.getStaffSandboxTestPort());
        data.setSandboxTransferPending(true);
        player.sendMessage(fmt.success("Connecting to the isolated staff test world..."));
        player.transfer(config.getStaffSandboxTestHost(), config.getStaffSandboxTestPort());
        return true;
    }

    private boolean transferToProduction(Player player) {
        if (!config.isStaffSandbox()) {
            player.sendMessage(fmt.info("You are already on the production server."));
            return true;
        }
        if (!config.isStaffSandboxTransferEnabled()) {
            player.sendMessage(fmt.error("Production return transfer is disabled in config.yml."));
            return true;
        }
        plugin.getAuditLogger().log(player.getUniqueId(), player.getName(), "STAFF_SANDBOX_RETURN", "PLAYER",
            player.getUniqueId().toString(), "host=" + config.getStaffSandboxProductionHost() + " port=" + config.getStaffSandboxProductionPort());
        player.sendMessage(fmt.success("Returning to production..."));
        player.transfer(config.getStaffSandboxProductionHost(), config.getStaffSandboxProductionPort());
        return true;
    }

    private boolean hasVsPermission(Player player, String permission) {
        if (config.isStaffSandbox() && config.getStaffSandboxAllowedUuids().contains(player.getUniqueId())) {
            return true;
        }
        if (player.hasPermission(permission)) {
            return true;
        }
        try {
            AccessService accessService = plugin.getServiceRegistry().get(AccessService.class);
            return accessService.hasPermission(player.getUniqueId(), permission);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return config.isStaffSandbox() ? List.of("return", "build") : List.of("test", "*", "build");
        }
        if (args.length == 2 && args[0].equals("*")) {
            return Bukkit.getOnlinePlayers().stream()
                .filter(p -> staffData.containsKey(p.getUniqueId())
                    && staffData.get(p.getUniqueId()).isStaffModeActive())
                .map(Player::getName)
                .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                .collect(Collectors.toList());
        }
        if (args[0].equalsIgnoreCase("build")) {
            if (args.length == 2) {
                List<String> suggestions = new ArrayList<>(List.of("on", "off"));
                staffData.forEach((uuid, data) -> {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null && data.isStaffModeActive()) suggestions.add(player.getName());
                });
                String prefix = args[1].toLowerCase(Locale.ROOT);
                return suggestions.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
            }
            if (args.length == 3) return List.of("on", "off", "toggle");
        }
        return List.of();
    }
}

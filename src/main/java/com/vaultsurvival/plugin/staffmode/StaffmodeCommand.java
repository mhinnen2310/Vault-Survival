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
            return true;
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

        data.setStaffModeActive(false);
        data.setBypassMode(false);
        data.clearBlockChanges();

        player.setGameMode(GameMode.SURVIVAL);
        if (data.getGameplayLocation() != null) player.teleport(data.getGameplayLocation());
        data.setGameplayLocation(null);
        player.sendMessage(fmt.success("Staff mode disabled. Gameplay inventory restored."));
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

        return true;
    }

    private boolean hasVsPermission(Player player, String permission) {
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
            return List.of("*");
        }
        if (args.length == 2 && args[0].equals("*")) {
            return Bukkit.getOnlinePlayers().stream()
                .filter(p -> staffData.containsKey(p.getUniqueId())
                    && staffData.get(p.getUniqueId()).isStaffModeActive())
                .map(Player::getName)
                .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                .collect(Collectors.toList());
        }
        return List.of();
    }
}

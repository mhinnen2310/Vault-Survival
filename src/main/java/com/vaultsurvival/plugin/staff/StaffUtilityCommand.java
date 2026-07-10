package com.vaultsurvival.plugin.staff;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Small, audited staffmode-only utility set; deliberately does not replace Essentials for players. */
public final class StaffUtilityCommand implements CommandExecutor, TabCompleter {
    private final VaultSurvivalPlugin plugin;
    private final Map<UUID, Location> returns = new HashMap<>();
    public StaffUtilityCommand(VaultSurvivalPlugin plugin) { this.plugin = plugin; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player staff)) return true;
        if (!staff.hasPermission("vs.staff.utility") || !plugin.isStaffModeActive(staff.getUniqueId())) {
            staff.sendMessage(plugin.getMessageFormatter().error("Activate staffmode and obtain vs.staff.utility.")); return true;
        }
        String name = command.getName().toLowerCase(Locale.ROOT);
        Player target = args.length == 0 ? staff : Bukkit.getPlayerExact(args[0]);
        switch (name) {
            case "vstp" -> {
                if (args.length < 1 || target == null) return error(staff, "/vstp <player>");
                returns.put(staff.getUniqueId(), staff.getLocation()); staff.teleportAsync(target.getLocation()); audit(staff, "STAFF_TP", target);
            }
            case "vstphere" -> {
                if (args.length < 1 || target == null) return error(staff, "/vstphere <player>");
                target.teleportAsync(staff.getLocation()); audit(staff, "STAFF_TPHERE", target);
            }
            case "vsback" -> {
                Location back = returns.remove(staff.getUniqueId()); if (back == null) return error(staff, "No staff teleport return location.");
                staff.teleportAsync(back); audit(staff, "STAFF_BACK", staff);
            }
            case "vsfly" -> { boolean enabled = !staff.getAllowFlight(); staff.setAllowFlight(enabled); if (!enabled) staff.setFlying(false); audit(staff, "STAFF_FLY_" + enabled, staff); }
            case "vsheal" -> { if (target == null) return error(staff, "Player not found."); target.setHealth(target.getMaxHealth()); target.setFoodLevel(20); target.setFireTicks(0); audit(staff, "STAFF_HEAL", target); }
            case "vsgamemode" -> {
                if (args.length < 1) return error(staff, "/vsgamemode <survival|creative|adventure|spectator> [player]");
                try { GameMode mode = GameMode.valueOf(args[0].toUpperCase(Locale.ROOT)); target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : staff; if (target == null) return error(staff, "Player not found."); target.setGameMode(mode); audit(staff, "STAFF_GAMEMODE_" + mode, target); }
                catch (IllegalArgumentException ex) { return error(staff, "Unknown gamemode."); }
            }
            default -> { return false; }
        }
        staff.sendMessage(plugin.getMessageFormatter().success("Staff action completed and audited.")); return true;
    }
    private boolean error(Player player, String message) { player.sendMessage(plugin.getMessageFormatter().error(message)); return true; }
    private void audit(Player actor, String action, Player target) { plugin.getAuditLogger().log(actor.getUniqueId(), actor.getName(), action, "PLAYER", target.getUniqueId().toString(), "staffmode=true"); }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("vsgamemode") && args.length == 1) return List.of("survival", "creative", "adventure", "spectator");
        return args.length <= 2 ? Bukkit.getOnlinePlayers().stream().map(Player::getName).toList() : List.of();
    }
}

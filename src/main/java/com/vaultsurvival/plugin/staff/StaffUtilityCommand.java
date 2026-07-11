package com.vaultsurvival.plugin.staff;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.access.AccessService;
import com.vaultsurvival.plugin.staffmode.StaffmodeData;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Audited staffmode-only utilities; deliberately unavailable during normal gameplay. */
public final class StaffUtilityCommand implements CommandExecutor, TabCompleter {
    private final VaultSurvivalPlugin plugin;
    private final Map<UUID, StaffmodeData> staffData;
    private final Map<UUID, Location> returns = new HashMap<>();

    public StaffUtilityCommand(VaultSurvivalPlugin plugin, Map<UUID, StaffmodeData> staffData) {
        this.plugin = plugin;
        this.staffData = staffData;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player staff)) {
            sender.sendMessage(plugin.getMessageFormatter().error("Only an in-game staff member can use this command."));
            return true;
        }
        if (!plugin.isStaffModeActive(staff.getUniqueId()) || !hasVsPermission(staff, "vs.staff.utility")) {
            audit(staff, "STAFF_UTILITY_DENIED", "PLAYER", staff.getUniqueId().toString(),
                "command=" + command.getName() + " staffmode=" + plugin.isStaffModeActive(staff.getUniqueId()));
            return error(staff, "Activate staffmode and obtain vs.staff.utility.");
        }

        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "vstp" -> teleport(staff, args);
            case "vstphere" -> teleportHere(staff, args);
            case "vsback" -> back(staff);
            case "vsfly" -> flight(staff, args);
            case "vsheal" -> heal(staff, args);
            case "vsgamemode" -> gameMode(staff, args);
            case "vstime" -> time(staff, args);
            case "vsweather" -> weather(staff, args);
            case "vsspeed" -> speed(staff, args);
            case "vsbreaker" -> breaker(staff, args);
            default -> false;
        };
    }

    private boolean teleport(Player staff, String[] args) {
        if (args.length < 1) return error(staff, "Usage: /tp <player>");
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) return error(staff, "Player not found: " + args[0]);
        returns.put(staff.getUniqueId(), staff.getLocation().clone());
        staff.teleportAsync(target.getLocation());
        audit(staff, "STAFF_TP", "PLAYER", target.getUniqueId().toString(), "target=" + target.getName());
        return success(staff, "Teleported to " + target.getName() + ". Use /back to return.");
    }

    private boolean teleportHere(Player staff, String[] args) {
        if (args.length < 1) return error(staff, "Usage: /tphere <player>");
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) return error(staff, "Player not found: " + args[0]);
        target.teleportAsync(staff.getLocation());
        audit(staff, "STAFF_TPHERE", "PLAYER", target.getUniqueId().toString(), "target=" + target.getName());
        return success(staff, "Teleported " + target.getName() + " to you.");
    }

    private boolean back(Player staff) {
        Location destination = returns.remove(staff.getUniqueId());
        if (destination == null) return error(staff, "No staff teleport return location is stored.");
        staff.teleportAsync(destination);
        audit(staff, "STAFF_BACK", "WORLD", destination.getWorld().getUID().toString(), locationDetail(destination));
        return success(staff, "Returned to your previous staff location.");
    }

    private boolean flight(Player staff, String[] args) {
        Boolean requested = args.length == 0 ? null : parseToggle(args[0]);
        if (args.length > 0 && requested == null) return error(staff, "Usage: /fly [on|off]");
        boolean enabled = requested == null ? !staff.getAllowFlight() : requested;
        staff.setAllowFlight(enabled);
        if (!enabled) staff.setFlying(false);
        audit(staff, "STAFF_FLY_" + (enabled ? "ENABLE" : "DISABLE"), "PLAYER",
            staff.getUniqueId().toString(), "enabled=" + enabled);
        return success(staff, "Flight " + (enabled ? "enabled" : "disabled") + ".");
    }

    private boolean heal(Player staff, String[] args) {
        Player target = args.length == 0 ? staff : Bukkit.getPlayerExact(args[0]);
        if (target == null) return error(staff, "Player not found: " + args[0]);
        target.setHealth(target.getMaxHealth());
        target.setFoodLevel(20);
        target.setSaturation(20.0f);
        target.setFireTicks(0);
        audit(staff, "STAFF_HEAL", "PLAYER", target.getUniqueId().toString(), "target=" + target.getName());
        return success(staff, target == staff ? "You have been healed." : "Healed " + target.getName() + ".");
    }

    private boolean gameMode(Player staff, String[] args) {
        if (args.length < 1) {
            return error(staff, "Usage: /gm <survival|creative|adventure|spectator> [player]");
        }
        GameMode mode = parseGameMode(args[0]);
        if (mode == null) return error(staff, "Unknown gamemode: " + args[0]);
        Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : staff;
        if (target == null) return error(staff, "Player not found: " + args[1]);
        target.setGameMode(mode);
        audit(staff, "STAFF_GAMEMODE", "PLAYER", target.getUniqueId().toString(),
            "target=" + target.getName() + " mode=" + mode.name());
        return success(staff, "Set " + target.getName() + " to " + mode.name().toLowerCase(Locale.ROOT) + ".");
    }

    private boolean time(Player staff, String[] args) {
        if (args.length < 1) {
            return error(staff, "Usage: /time <day|noon|sunset|night|midnight|sunrise|set <ticks>|add <ticks>>");
        }
        World world = staff.getWorld();
        long before = world.getTime();
        long requested;
        String operation = args[0].toLowerCase(Locale.ROOT);
        try {
            requested = switch (operation) {
                case "day" -> 1000L;
                case "noon" -> 6000L;
                case "sunset" -> 12000L;
                case "night" -> 13000L;
                case "midnight" -> 18000L;
                case "sunrise" -> 23000L;
                case "set" -> args.length >= 2 ? Long.parseLong(args[1]) : Long.MIN_VALUE;
                case "add" -> args.length >= 2 ? Math.addExact(before, Long.parseLong(args[1])) : Long.MIN_VALUE;
                default -> Long.parseLong(args[0]);
            };
        } catch (NumberFormatException | ArithmeticException ex) {
            return error(staff, "Time must be a preset or a whole tick value.");
        }
        if (requested == Long.MIN_VALUE) return error(staff, "Provide a tick value after " + operation + ".");
        long normalized = Math.floorMod(requested, 24000L);
        world.setTime(normalized);
        audit(staff, "STAFF_TIME_SET", "WORLD", world.getUID().toString(),
            "world=" + world.getName() + " before=" + before + " after=" + normalized + " operation=" + operation);
        return success(staff, "Set time in " + world.getName() + " to " + normalized + " ticks.");
    }

    private boolean weather(Player staff, String[] args) {
        if (args.length < 1) return error(staff, "Usage: /weather <clear|rain|thunder> [seconds]");
        int maxDuration = plugin.getConfigManager().getStaffWeatherMaxDurationSeconds();
        int seconds = 600;
        if (args.length >= 2) {
            try {
                seconds = Integer.parseInt(args[1]);
            } catch (NumberFormatException ex) {
                return error(staff, "Weather duration must be a whole number of seconds.");
            }
        }
        if (seconds < 1 || seconds > maxDuration) {
            return error(staff, "Weather duration must be between 1 and " + maxDuration + " seconds.");
        }

        World world = staff.getWorld();
        String type = args[0].toLowerCase(Locale.ROOT);
        int ticks = seconds * 20;
        switch (type) {
            case "clear", "sun", "sunny" -> {
                type = "clear";
                world.setStorm(false);
                world.setThundering(false);
                world.setClearWeatherDuration(ticks);
            }
            case "rain", "rainy" -> {
                type = "rain";
                world.setStorm(true);
                world.setThundering(false);
                world.setWeatherDuration(ticks);
            }
            case "thunder", "storm" -> {
                type = "thunder";
                world.setStorm(true);
                world.setThundering(true);
                world.setWeatherDuration(ticks);
                world.setThunderDuration(ticks);
            }
            default -> {
                return error(staff, "Unknown weather. Choose clear, rain, or thunder.");
            }
        }
        audit(staff, "STAFF_WEATHER_SET", "WORLD", world.getUID().toString(),
            "world=" + world.getName() + " weather=" + type + " seconds=" + seconds);
        return success(staff, "Set " + world.getName() + " weather to " + type + " for " + seconds + " seconds.");
    }

    private boolean speed(Player staff, String[] args) {
        if (args.length < 1) {
            return error(staff, "Usage: /speed <0-10|reset> [walk|fly]");
        }
        String movement = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : (staff.isFlying() ? "fly" : "walk");
        if (!movement.equals("walk") && !movement.equals("fly")) {
            return error(staff, "Movement type must be walk or fly.");
        }

        double logicalSpeed;
        float bukkitSpeed;
        if (args[0].equalsIgnoreCase("reset")) {
            logicalSpeed = movement.equals("fly") ? 1.0 : 2.0;
            bukkitSpeed = movement.equals("fly") ? 0.1f : 0.2f;
        } else {
            try {
                logicalSpeed = Double.parseDouble(args[0]);
            } catch (NumberFormatException ex) {
                return error(staff, "Speed must be a number or reset.");
            }
            double min = plugin.getConfigManager().getStaffSpeedMin();
            double max = plugin.getConfigManager().getStaffSpeedMax();
            if (!Double.isFinite(logicalSpeed) || logicalSpeed < min || logicalSpeed > max) {
                return error(staff, "Speed must be between " + cleanNumber(min) + " and " + cleanNumber(max) + ".");
            }
            bukkitSpeed = (float) Math.max(-1.0, Math.min(1.0, logicalSpeed / 10.0));
        }

        if (movement.equals("fly")) staff.setFlySpeed(bukkitSpeed);
        else staff.setWalkSpeed(bukkitSpeed);
        audit(staff, "STAFF_SPEED_SET", "PLAYER", staff.getUniqueId().toString(),
            "type=" + movement + " logical=" + logicalSpeed + " bukkit=" + bukkitSpeed);
        return success(staff, "Set " + movement + " speed to " + cleanNumber(logicalSpeed) + ".");
    }

    private boolean breaker(Player staff, String[] args) {
        StaffmodeData data = staffData.get(staff.getUniqueId());
        if (data == null || !data.isStaffModeActive()) return error(staff, "Activate staffmode first.");
        if (args.length < 1) {
            String current = data.getBreakerSize() == 0 ? "off" : data.getBreakerSize() + "x" + data.getBreakerSize();
            return error(staff, "Breaker is " + current + ". Usage: /breaker <3x3..9x9|off>");
        }
        if (args[0].equalsIgnoreCase("off") || args[0].equalsIgnoreCase("disable")) {
            int previous = data.getBreakerSize();
            data.setBreakerSize(0);
            audit(staff, "STAFF_BREAKER_DISABLE", "PLAYER", staff.getUniqueId().toString(), "previousSize=" + previous);
            return success(staff, "Area breaker disabled.");
        }

        Integer size = parseBreakerSize(args[0]);
        List<Integer> allowed = plugin.getConfigManager().getStaffBreakerAllowedSizes();
        if (size == null || !allowed.contains(size)) {
            return error(staff, "Allowed breaker sizes: " + allowed.stream().map(value -> value + "x" + value).toList());
        }
        data.setBreakerSize(size);
        audit(staff, "STAFF_BREAKER_ENABLE", "PLAYER", staff.getUniqueId().toString(),
            "size=" + size + " persistentBuild=" + plugin.hasStaffBuildPermission(staff.getUniqueId()));
        staff.sendMessage(plugin.getMessageFormatter().warn("Protected blocks and containers are skipped. Without an owner build grant, changes revert when staffmode ends."));
        return success(staff, size + "x" + size + " area breaker enabled.");
    }

    private Integer parseBreakerSize(String input) {
        String normalized = input.toLowerCase(Locale.ROOT).trim();
        if (normalized.contains("x")) {
            String[] dimensions = normalized.split("x", -1);
            if (dimensions.length != 2 || !dimensions[0].equals(dimensions[1])) return null;
            normalized = dimensions[0];
        }
        try {
            return Integer.parseInt(normalized);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private GameMode parseGameMode(String input) {
        return switch (input.toLowerCase(Locale.ROOT)) {
            case "0", "s", "survival" -> GameMode.SURVIVAL;
            case "1", "c", "creative" -> GameMode.CREATIVE;
            case "2", "a", "adventure" -> GameMode.ADVENTURE;
            case "3", "sp", "spectator" -> GameMode.SPECTATOR;
            default -> null;
        };
    }

    private Boolean parseToggle(String input) {
        return switch (input.toLowerCase(Locale.ROOT)) {
            case "on", "enable", "enabled", "true" -> true;
            case "off", "disable", "disabled", "false" -> false;
            default -> null;
        };
    }

    private boolean hasVsPermission(Player player, String permission) {
        if (plugin.getConfigManager().isStaffSandbox()
            && plugin.getConfigManager().getStaffSandboxAllowedUuids().contains(player.getUniqueId())) return true;
        if (player.hasPermission(permission)) return true;
        try {
            return plugin.getServiceRegistry().get(AccessService.class).hasPermission(player.getUniqueId(), permission);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean error(Player player, String message) {
        player.sendMessage(plugin.getMessageFormatter().error(message));
        return true;
    }

    private boolean success(Player player, String message) {
        player.sendMessage(plugin.getMessageFormatter().success(message));
        return true;
    }

    private void audit(Player actor, String action, String targetType, String targetId, String details) {
        plugin.getAuditLogger().log(actor.getUniqueId(), actor.getName(), action, targetType, targetId,
            details + " staffmode=true");
    }

    private String locationDetail(Location location) {
        return "world=" + location.getWorld().getName() + " x=" + location.getBlockX()
            + " y=" + location.getBlockY() + " z=" + location.getBlockZ();
    }

    private String cleanNumber(double number) {
        return number == Math.rint(number) ? Long.toString(Math.round(number)) : Double.toString(number);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || !plugin.isStaffModeActive(player.getUniqueId())) return List.of();
        String name = command.getName().toLowerCase(Locale.ROOT);
        List<String> suggestions = new ArrayList<>();
        switch (name) {
            case "vsgamemode" -> {
                if (args.length == 1) suggestions.addAll(List.of("survival", "creative", "adventure", "spectator"));
                else if (args.length == 2) addOnlinePlayers(suggestions);
            }
            case "vstp", "vstphere", "vsheal" -> {
                if (args.length == 1) addOnlinePlayers(suggestions);
            }
            case "vsfly" -> {
                if (args.length == 1) suggestions.addAll(List.of("on", "off"));
            }
            case "vstime" -> {
                if (args.length == 1) suggestions.addAll(List.of("day", "noon", "sunset", "night", "midnight", "sunrise", "set", "add"));
                else if (args.length == 2 && (args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("add"))) suggestions.add("1000");
            }
            case "vsweather" -> {
                if (args.length == 1) suggestions.addAll(List.of("clear", "rain", "thunder"));
                else if (args.length == 2) suggestions.addAll(List.of("60", "600", "3600"));
            }
            case "vsspeed" -> {
                if (args.length == 1) suggestions.addAll(List.of("1", "2", "5", "10", "reset"));
                else if (args.length == 2) suggestions.addAll(List.of("walk", "fly"));
            }
            case "vsbreaker" -> {
                if (args.length == 1) {
                    suggestions.add("off");
                    plugin.getConfigManager().getStaffBreakerAllowedSizes().forEach(size -> suggestions.add(size + "x" + size));
                }
            }
            default -> { }
        }
        if (args.length == 0) return suggestions;
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return suggestions.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }

    private void addOnlinePlayers(List<String> suggestions) {
        Bukkit.getOnlinePlayers().stream().map(Player::getName).forEach(suggestions::add);
    }
}

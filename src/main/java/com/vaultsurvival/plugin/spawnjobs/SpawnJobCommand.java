package com.vaultsurvival.plugin.spawnjobs;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;

public class SpawnJobCommand implements CommandExecutor, TabCompleter {
    private final SpawnJobService service;
    private final MessageFormatter fmt;

    public SpawnJobCommand(VaultSurvivalPlugin plugin) {
        this.service = plugin.getServiceRegistry().get(SpawnJobService.class);
        this.fmt = plugin.getMessageFormatter();
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage(fmt.error("Only players can use spawn jobs.")); return true; }
        if (args.length == 0) return board(player);
        if (args[0].equalsIgnoreCase("active")) return active(player);
        if (args[0].equalsIgnoreCase("accept") && args.length >= 2) {
            player.sendMessage(service.accept(player, parseInt(args[1])) ? fmt.success("Spawn job accepted.") : fmt.error("Cannot accept spawn job."));
            return true;
        }
        if ((args[0].equalsIgnoreCase("turnin") || args[0].equalsIgnoreCase("complete")) && args.length >= 2) {
            player.sendMessage(service.turnIn(player, parseInt(args[1])) ? fmt.success("Spawn job completed.") : fmt.error("Cannot turn in this job yet."));
            return true;
        }
        if (args[0].equalsIgnoreCase("abandon") && args.length >= 2) {
            player.sendMessage(service.abandon(player, parseInt(args[1])) ? fmt.success("Spawn job abandoned.") : fmt.error("Cannot abandon job."));
            return true;
        }
        if (args[0].equalsIgnoreCase("admin")) return admin(player, args);
        return board(player);
    }

    private boolean board(Player player) {
        player.sendMessage(fmt.header("Spawn Job Board"));
        player.sendMessage(fmt.info("Starter Jobs / Transport Jobs / Delivery Jobs / Exploration Jobs"));
        for (var job : service.getJobs()) {
            player.sendMessage(fmt.info("&e#" + job.getId() + " &7" + job.getType() + " &8| &f" + job.getTitle()
                + " &8| &6" + job.getReward() + (job.getRequiredItem() != null ? " &8| &7" + job.getRequiredAmount() + "x " + job.getRequiredItem() : "")));
        }
        player.sendMessage(fmt.info("Use &e/spawnjobs accept <id>&7, then &e/spawnjobs turnin <id>&7."));
        return true;
    }

    private boolean active(Player player) {
        var active = service.getActiveJobs(player);
        player.sendMessage(fmt.header("My Active Spawn Jobs (" + active.size() + ")"));
        for (var pj : active) {
            var job = service.getJob(pj.getJobId());
            if (job != null) player.sendMessage(fmt.info("&e#" + job.getId() + " &7" + job.getTitle() + " &8| &7" + pj.getStatus()));
        }
        return true;
    }

    private boolean admin(Player player, String[] args) {
        if (!player.hasPermission("vs.admin")) { player.sendMessage(fmt.permissionDenied()); return true; }
        if (args.length == 1 || args[1].equalsIgnoreCase("list")) return board(player);
        if (args[1].equalsIgnoreCase("disable") && args.length >= 3) {
            player.sendMessage(service.disableJob(parseInt(args[2])) ? fmt.success("Spawn job disabled.") : fmt.error("Could not disable job."));
            return true;
        }
        if (args[1].equalsIgnoreCase("create") && args.length >= 7) {
            SpawnJobData.JobType type = parseType(args[2]);
            long reward = parseLong(args[3]);
            String item = args[4].equalsIgnoreCase("none") ? null : args[4];
            int amount = parseInt(args[5]);
            String title = String.join(" ", Arrays.copyOfRange(args, 6, args.length));
            if (type == null || (item != null && service.parseItem(item) == null)) {
                player.sendMessage(fmt.error("Invalid type or item."));
                return true;
            }
            var job = service.createAdminJob(type, title, reward, item, amount, "Spawn Job Board");
            player.sendMessage(job != null ? fmt.success("Created spawn job #" + job.getId()) : fmt.error("Could not create spawn job."));
            return true;
        }
        player.sendMessage(fmt.error("Usage: /spawnjobs admin create <type> <reward> <item|none> <amount> <title>"));
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("active", "accept", "turnin", "abandon", "admin").stream().filter(v -> v.startsWith(args[0].toLowerCase())).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) return List.of("create", "list", "disable").stream().filter(v -> v.startsWith(args[1].toLowerCase())).toList();
        if (args.length == 3 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("create")) return Arrays.stream(SpawnJobData.JobType.values()).map(Enum::name).filter(v -> v.startsWith(args[2].toUpperCase())).toList();
        return List.of();
    }

    private SpawnJobData.JobType parseType(String raw) { try { return SpawnJobData.JobType.valueOf(raw.toUpperCase()); } catch (Exception ignored) { return null; } }
    private int parseInt(String raw) { try { return Integer.parseInt(raw); } catch (Exception ignored) { return -1; } }
    private long parseLong(String raw) { try { return Long.parseLong(raw); } catch (Exception ignored) { return -1; } }
}

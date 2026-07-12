package com.vaultsurvival.plugin.spawnjobs;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.Material;
import com.vaultsurvival.plugin.dialogs.DialogMenuItem;
import com.vaultsurvival.plugin.dialogs.DialogService;
import com.vaultsurvival.plugin.npc.NpcService;
import java.util.ArrayList;

import java.util.Arrays;
import java.util.List;

public class SpawnJobCommand implements CommandExecutor, TabCompleter {
    private final SpawnJobService service;
    private final VaultSurvivalPlugin plugin;
    private final MessageFormatter fmt;

    public SpawnJobCommand(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.service = plugin.getServiceRegistry().get(SpawnJobService.class);
        this.fmt = plugin.getMessageFormatter();
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage(fmt.error("Only players can use spawn jobs.")); return true; }
        boolean adminFallback = args.length > 0 && args[0].equalsIgnoreCase("admin") && player.hasPermission("vs.admin");
        if (!adminFallback && !hasJobBoardSession(player)) {
            DialogService dialogs = dialogs();
            if (dialogs != null) {
                dialogs.openResult(player, "Job Board NPC Required",
                    "Job boards are physical. Interact with a Job Board NPC to browse or manage jobs.",
                    List.of(DialogMenuItem.item("Back to Main Menu", "Return without opening a job board.", "vsmenu", null, Material.ARROW)));
            } else {
                player.sendMessage(fmt.error("Interact with a Job Board NPC first."));
            }
            return true;
        }
        if (args.length == 0) return board(player);
        if (args[0].equalsIgnoreCase("active")) return active(player);
        if (args[0].equalsIgnoreCase("accept") && args.length >= 2) {
            jobActionResult(player, "Accept Spawn Job", service.accept(player, parseInt(args[1])),
                "Spawn job accepted.", "This job cannot be accepted.");
            return true;
        }
        if ((args[0].equalsIgnoreCase("turnin") || args[0].equalsIgnoreCase("complete")) && args.length >= 2) {
            jobActionResult(player, "Turn In Spawn Job", service.turnIn(player, parseInt(args[1])),
                "Spawn job completed; its payout has been processed.", "This job cannot be turned in yet.");
            return true;
        }
        if (args[0].equalsIgnoreCase("abandon") && args.length >= 2) {
            jobActionResult(player, "Abandon Spawn Job", service.abandon(player, parseInt(args[1])),
                "Spawn job abandoned.", "This job cannot be abandoned.");
            return true;
        }
        if (args[0].equalsIgnoreCase("admin")) return admin(player, args);
        return board(player);
    }

    private boolean board(Player player) {
        DialogService dialogs = dialogs();
        if (dialogs != null) {
            List<DialogMenuItem> items = new ArrayList<>();
            for (var job : service.getJobs()) items.add(DialogMenuItem.item("#" + job.getId() + " " + job.getTitle(), job.getType() + " | Reward " + job.getReward(), "spawnjobs accept " + job.getId(), null, job.getType() == SpawnJobData.JobType.TRANSPORT_PACKAGE ? Material.PAPER : Material.CHEST));
            items.add(DialogMenuItem.item("My Active Jobs", "View and turn in active jobs.", "spawnjobs active", null, Material.MAP));
            items.add(DialogMenuItem.item("District Job Board", "Browse jobs offered by your district.", "district jobs", null, Material.LECTERN));
            dialogs.openResult(player, "Spawn Job Board", "Starter jobs pay physical cash. Transport packages are unique and cannot be stored.", items);
            return true;
        }
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
        DialogService dialogs = dialogs();
        if (dialogs != null) {
            List<DialogMenuItem> items = new ArrayList<>();
            for (var pj : active) { var job = service.getJob(pj.getJobId()); if (job != null) items.add(DialogMenuItem.item("#" + job.getId() + " " + job.getTitle(), "Turn in when requirements are met.", "spawnjobs turnin " + job.getId(), null, Material.CHEST)); }
            items.add(DialogMenuItem.item("Job Board", "Return to starter jobs.", "spawnjobs", null, Material.BELL));
            dialogs.openResult(player, "My Active Spawn Jobs", active.isEmpty() ? "No active jobs." : "Select a job to turn it in.", items);
            return true;
        }
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
    private DialogService dialogs() { try { return plugin.getServiceRegistry().get(DialogService.class); } catch (RuntimeException ignored) { return null; } }
    private void jobActionResult(Player player, String title, boolean success, String successMessage, String errorMessage) {
        DialogService dialogs = dialogs();
        if (dialogs != null) {
            dialogs.openResult(player, success ? title + " — Success" : title + " — Failed",
                success ? successMessage : errorMessage,
                List.of(
                    DialogMenuItem.item("My Active Jobs", "Refresh active jobs.", "spawnjobs active", null, Material.MAP),
                    DialogMenuItem.item("Job Board", "Return to the NPC-opened board.", "spawnjobs", null, Material.BELL)));
        } else {
            player.sendMessage(success ? fmt.success(successMessage) : fmt.error(errorMessage));
        }
    }
    private boolean hasJobBoardSession(Player player) {
        try { return plugin.getServiceRegistry().get(NpcService.class).hasJobBoardSession(player.getUniqueId()); }
        catch (RuntimeException ignored) { return false; }
    }
}

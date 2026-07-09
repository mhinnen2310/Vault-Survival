package com.vaultsurvival.plugin.districts;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.area.CurrentAreaContext;
import com.vaultsurvival.plugin.area.CurrentAreaService;
import com.vaultsurvival.plugin.core.MessageFormatter;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

/**
 * District management commands.
 *
 * /district apply <name>              — Submit a district application
 * /district approve <id>              — Admin approve application
 * /district reject <id> <reason>      — Admin reject application
 * /district info [id]                 — District info (your district or specific)
 * /district invite <player>           — Invite player to your district
 * /district kick <player>             — Kick member from your district
 * /district role <player> <role>      — Set member role (mayor only)
 * /district deposit <amount>          — Deposit cash into treasury
 * /district withdraw <amount>         — Withdraw from treasury (treasurer+)
 * /district law <name> <true|false>   — Toggle a local law (council+)
 * /district list                      — List all districts
 * /district disband                   — Disband your district (mayor only)
 * /district applications              — List pending applications (admin)
 */
public class DistrictCommand implements CommandExecutor, TabCompleter {

    private final VaultSurvivalPlugin plugin;
    private final DistrictService districtService;
    private final MessageFormatter fmt;

    public DistrictCommand(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.districtService = plugin.getServiceRegistry().get(DistrictService.class);
        this.fmt = plugin.getMessageFormatter();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "apply" -> handleApply(sender, args);
            case "current" -> handleCurrent(sender);
            case "approve" -> handleApprove(sender, args);
            case "reject" -> handleReject(sender, args);
            case "info" -> handleInfo(sender, args);
            case "invite" -> handleInvite(sender, args);
            case "kick" -> handleKick(sender, args);
            case "role" -> handleRole(sender, args);
            case "permissions" -> handlePermissions(sender);
            case "members" -> handleMembers(sender);
            case "deposit" -> handleDeposit(sender, args);
            case "withdraw" -> handleWithdraw(sender, args);
            case "laws" -> handleLaws(sender, args);
            case "law" -> handleLaw(sender, args);
            case "jobs" -> handleJobs(sender);
            case "job" -> handleJob(sender, args);
            case "list" -> handleList(sender);
            case "disband" -> handleDisband(sender);
            case "applications" -> handleApplications(sender);
            default -> { sendUsage(sender); yield true; }
        };
    }

    private boolean handleApply(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(fmt.error("Only players can apply for a district."));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(fmt.error("Usage: /district apply <name>"));
            return true;
        }
        String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        districtService.apply(player, name);
        return true;
    }

    private boolean handleCurrent(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(fmt.error("Only players can inspect current district context."));
            return true;
        }
        CurrentAreaService currentAreaService = plugin.getServiceRegistry().get(CurrentAreaService.class);
        CurrentAreaContext context = currentAreaService.resolve(player);

        sender.sendMessage(fmt.header("Current District"));
        sender.sendMessage(fmt.info("Area: &e" + context.areaType().name().replace('_', ' ') + " &8- &7" + context.areaName()));
        if (!context.hasDistrict()) {
            sender.sendMessage(fmt.info("District: &eNone"));
            sender.sendMessage(fmt.info("Status: &eVISITOR"));
            sender.sendMessage(fmt.info("Use &e/whereami &7for full area and region context."));
            return true;
        }

        DistrictData.District district = context.district();
        sender.sendMessage(fmt.info("District: &e" + district.getName() + " &8(#" + district.getId() + ")"));
        sender.sendMessage(fmt.info("Your status here: &e" + context.playerStatus()));
        sender.sendMessage(fmt.info("District status: &e" + district.getStatus()));
        sender.sendMessage(fmt.info("Laws: &7" + context.lawSummary()));
        sender.sendMessage(fmt.warn("Illegal actions are not automatically blocked. They create evidence if district law/evidence systems are active."));
        return true;
    }

    private boolean handleApprove(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vs.district.admin")) {
            sender.sendMessage(fmt.permissionDenied());
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(fmt.error("Usage: /district approve <id>"));
            return true;
        }
        int id = parseInt(args[1]);
        UUID adminUuid = sender instanceof Player p ? p.getUniqueId() : UUID.fromString("00000000-0000-0000-0000-000000000000");
        if (districtService.approve(id, adminUuid)) {
            sender.sendMessage(fmt.success("District #" + id + " approved!"));
        } else {
            sender.sendMessage(fmt.error("District not found or not in application status."));
        }
        return true;
    }

    private boolean handleReject(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vs.district.admin")) {
            sender.sendMessage(fmt.permissionDenied());
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(fmt.error("Usage: /district reject <id> <reason>"));
            return true;
        }
        int id = parseInt(args[1]);
        String reason = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        UUID adminUuid = sender instanceof Player p ? p.getUniqueId() : UUID.fromString("00000000-0000-0000-0000-000000000000");
        if (districtService.reject(id, adminUuid, reason)) {
            sender.sendMessage(fmt.success("District #" + id + " rejected."));
        } else {
            sender.sendMessage(fmt.error("District not found or not in application status."));
        }
        return true;
    }

    private boolean handleInfo(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            int id = parseInt(args[1]);
            var d = districtService.getDistrict(id);
            if (d == null) { sender.sendMessage(fmt.error("District not found.")); return true; }
            showDistrictInfo(sender, d);
        } else if (sender instanceof Player player) {
            var d = districtService.getPlayerDistrict(player.getUniqueId());
            if (d == null) {
                sender.sendMessage(fmt.info("You are not in a district. Found one with &e/district apply <name>"));
            } else {
                showDistrictInfo(sender, d);
            }
        }
        return true;
    }

    private boolean handleInvite(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length < 2) {
            sender.sendMessage(fmt.error("Usage: /district invite <player>"));
            return true;
        }
        var d = districtService.getPlayerDistrict(player.getUniqueId());
        if (d == null) { sender.sendMessage(fmt.error("You are not in a district.")); return true; }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) { sender.sendMessage(fmt.playerNotFound(args[1])); return true; }
        if (districtService.inviteMember(d.getId(), player.getUniqueId(), target.getUniqueId())) {
            player.sendMessage(fmt.success("Invited &e" + target.getName() + " &ato join your district!"));
            target.sendMessage(fmt.success("You've been invited to join &e" + d.getName() + "&a!"));
        }
        return true;
    }

    private boolean handleKick(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length < 2) {
            sender.sendMessage(fmt.error("Usage: /district kick <player>"));
            return true;
        }
        var d = districtService.getPlayerDistrict(player.getUniqueId());
        if (d == null) { sender.sendMessage(fmt.error("You are not in a district.")); return true; }
        // Look up member UUID by name from district's actual member list
        String targetName = args[1];
        UUID targetUuid = null;
        for (UUID memberUuid : d.getMembers()) {
            @SuppressWarnings("deprecation")
            String name = Bukkit.getOfflinePlayer(memberUuid).getName();
            if (targetName.equalsIgnoreCase(name)) {
                targetUuid = memberUuid;
                break;
            }
        }
        if (targetUuid == null) {
            player.sendMessage(fmt.error("That player is not a member of your district."));
            return true;
        }
        if (districtService.kickMember(d.getId(), player.getUniqueId(), targetUuid)) {
            player.sendMessage(fmt.success("Kicked &e" + targetName + " &afrom the district."));
        } else {
            player.sendMessage(fmt.error("Cannot kick that player. You need council+ rank, and you cannot kick the mayor."));
        }
        return true;
    }

    private boolean handleRole(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length < 3) {
            sender.sendMessage(fmt.error("Usage: /district role <list|set|remove> <player> [role]"));
            sendRoleList(sender);
            return true;
        }
        var d = districtService.getPlayerDistrict(player.getUniqueId());
        if (d == null) { sender.sendMessage(fmt.error("You are not in a district.")); return true; }

        String action = args[1].toLowerCase(Locale.ROOT);
        String targetName = args[2];
        UUID targetUuid = findDistrictMemberUuid(d, targetName);
        if (targetUuid == null) {
            player.sendMessage(fmt.error("That player is not a member of your district."));
            return true;
        }

        if (action.equals("list")) {
            sender.sendMessage(fmt.header("District Roles: " + targetName));
            sender.sendMessage(fmt.info("Highest: &e" + districtService.getHighestDistrictRole(targetUuid, d).name()));
            sender.sendMessage(fmt.info("Roles: &e" + districtService.getDistrictRoles(targetUuid, d).stream()
                .map(Enum::name).collect(Collectors.joining(", "))));
            return true;
        }

        if (args.length < 4) {
            sender.sendMessage(fmt.error("Usage: /district role " + action + " <player> <role>"));
            sendRoleList(sender);
            return true;
        }

        DistrictData.DistrictRole role = parseRole(args[3]);
        if (role == null || role == DistrictData.DistrictRole.VISITOR) {
            sender.sendMessage(fmt.error("Unknown or invalid assignable role."));
            sendRoleList(sender);
            return true;
        }

        if (action.equals("set")) {
            if (districtService.setRole(d.getId(), player.getUniqueId(), targetUuid, role)) {
                player.sendMessage(fmt.success("Added role &e" + role.name() + " &ato &e" + targetName));
            } else {
                player.sendMessage(fmt.error("You need MAYOR/CO_MAYOR, and only MAYOR can grant MAYOR."));
            }
            return true;
        }

        if (action.equals("remove")) {
            if (districtService.removeRole(d.getId(), player.getUniqueId(), targetUuid, role)) {
                player.sendMessage(fmt.success("Removed role &e" + role.name() + " &afrom &e" + targetName));
            } else {
                player.sendMessage(fmt.error("Could not remove that role. MAYOR cannot be removed this way."));
            }
            return true;
        }

        sender.sendMessage(fmt.error("Use: list, set, or remove"));
        return true;
    }

    private boolean handlePermissions(CommandSender sender) {
        if (!(sender instanceof Player player)) return true;
        var d = districtService.getPlayerDistrict(player.getUniqueId());
        if (d == null) { sender.sendMessage(fmt.error("You are not in a district.")); return true; }
        UUID uuid = player.getUniqueId();
        sender.sendMessage(fmt.header("District Permissions"));
        sender.sendMessage(fmt.info("Roles: &e" + districtService.getDistrictRoles(uuid, d).stream()
            .map(Enum::name).collect(Collectors.joining(", "))));
        sender.sendMessage(fmt.info("Manage roles: &e" + districtService.canManageRoles(uuid, d)));
        sender.sendMessage(fmt.info("Manage laws: &e" + districtService.canManageLaws(uuid, d)));
        sender.sendMessage(fmt.info("Manage treasury: &e" + districtService.canManageTreasury(uuid, d)));
        sender.sendMessage(fmt.info("Create merchant NPC: &e" + districtService.canCreateMerchantNpc(uuid, d)));
        sender.sendMessage(fmt.info("Create district job: &e" + districtService.canCreateDistrictJob(uuid, d)));
        sender.sendMessage(fmt.info("Approve district job: &e" + districtService.canApproveDistrictJob(uuid, d)));
        sender.sendMessage(fmt.info("Police: &e" + districtService.canPolice(uuid, d)));
        sender.sendMessage(fmt.info("Request station: &e" + districtService.canRequestStation(uuid, d)));
        sender.sendMessage(fmt.info("Manage development: &e" + districtService.canManageDevelopment(uuid, d)));
        return true;
    }

    private boolean handleMembers(CommandSender sender) {
        if (!(sender instanceof Player player)) return true;
        var d = districtService.getPlayerDistrict(player.getUniqueId());
        if (d == null) { sender.sendMessage(fmt.error("You are not in a district.")); return true; }
        sender.sendMessage(fmt.header("District Members: " + d.getName()));
        for (UUID memberUuid : d.getMembers()) {
            @SuppressWarnings("deprecation")
            String name = Bukkit.getOfflinePlayer(memberUuid).getName();
            if (name == null) name = memberUuid.toString().substring(0, 8);
            sender.sendMessage(fmt.info("&e" + name + " &8- &7" + d.getRoles(memberUuid).stream()
                .map(Enum::name).collect(Collectors.joining(", "))));
        }
        return true;
    }

    private boolean handleDeposit(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length < 2) {
            sender.sendMessage(fmt.error("Usage: /district deposit <amount>"));
            return true;
        }
        var d = districtService.getPlayerDistrict(player.getUniqueId());
        if (d == null) { sender.sendMessage(fmt.error("You are not in a district.")); return true; }
        long amount = parseLong(args[1]);
        if (amount <= 0) { sender.sendMessage(fmt.error("Invalid amount.")); return true; }
        districtService.depositTreasury(player, d.getId(), amount);
        return true;
    }

    private boolean handleWithdraw(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length < 2) {
            sender.sendMessage(fmt.error("Usage: /district withdraw <amount>"));
            return true;
        }
        var d = districtService.getPlayerDistrict(player.getUniqueId());
        if (d == null) { sender.sendMessage(fmt.error("You are not in a district.")); return true; }
        long amount = parseLong(args[1]);
        if (amount <= 0) { sender.sendMessage(fmt.error("Invalid amount.")); return true; }
        districtService.withdrawTreasury(player, d.getId(), amount);
        return true;
    }

    private boolean handleLaw(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length < 4 || !args[1].equalsIgnoreCase("propose")) {
            sender.sendMessage(fmt.error("Usage: /district law propose <law> <true|false>"));
            return true;
        }
        var d = districtService.getPlayerDistrict(player.getUniqueId());
        if (d == null) { sender.sendMessage(fmt.error("You are not in a district.")); return true; }
        DistrictData.LawKey lawKey = parseLaw(args[2]);
        if (lawKey == null) {
            sender.sendMessage(fmt.error("Unknown law. Use /district laws to see valid laws."));
            return true;
        }
        boolean enabled = args[3].equalsIgnoreCase("true");
        if (districtService.proposeLaw(d.getId(), player.getUniqueId(), lawKey, enabled)) {
            player.sendMessage(fmt.success("Pending law change proposed: &e" + lawKey.name() + " &7= &e" + enabled));
            player.sendMessage(fmt.info("It will apply at the daily law reload."));
        } else {
            player.sendMessage(fmt.error("Could not propose law. You may lack permission or hit the daily pending-change limit."));
        }
        return true;
    }

    private boolean handleLaws(CommandSender sender, String[] args) {
        DistrictData.District d = null;
        if (sender instanceof Player player) {
            d = districtService.getPlayerDistrict(player.getUniqueId());
        }
        if (d == null && args.length >= 2 && !args[1].equalsIgnoreCase("pending")) {
            d = districtService.getDistrict(parseInt(args[1]));
        }
        if (d == null) {
            sender.sendMessage(fmt.error("No district found. Join a district or use /district info <id>."));
            return true;
        }
        boolean pending = args.length >= 2 && args[1].equalsIgnoreCase("pending");
        sender.sendMessage(fmt.header((pending ? "Pending Laws: " : "Active Laws: ") + d.getName()));
        Map<String, Boolean> laws = pending ? d.getPendingLaws() : d.getLaws();
        for (DistrictData.LawKey law : DistrictData.LawKey.values()) {
            Boolean value = laws.get(law.name());
            if (pending) {
                if (value != null) {
                    sender.sendMessage(fmt.info("&e" + law.name() + " &8=> &f" + value));
                }
            } else {
                sender.sendMessage(fmt.info((Boolean.TRUE.equals(value) ? "&aON " : "&cOFF") + " &7" + law.name()));
            }
        }
        if (pending && laws.isEmpty()) {
            sender.sendMessage(fmt.info("No pending law changes."));
        }
        return true;
    }

    private boolean handleList(CommandSender sender) {
        var all = districtService.getAllDistricts();
        sender.sendMessage(fmt.header("Districts (" + all.size() + ")"));
        if (all.isEmpty()) {
            sender.sendMessage(fmt.info("No districts yet. Found one with &e/district apply <name>"));
        } else {
            for (var d : all) {
                sender.sendMessage(fmt.info(
                    "&e#" + d.getId() + " &f" + d.getName() +
                    " &7(" + d.getStatus() + ") &8| " +
                    d.getMemberCount() + " members &8| " +
                    "&6" + fmt.formatMoney(d.getTreasuryBalance(),
                        plugin.getConfigManager().getCurrencyName(),
                        plugin.getConfigManager().getCurrencyNamePlural())
                ));
            }
        }
        return true;
    }

    private boolean handleJobs(CommandSender sender) {
        if (!(sender instanceof Player player)) return true;
        DistrictJobService jobs = getJobService(sender);
        if (jobs == null) return true;
        var d = districtService.getPlayerDistrict(player.getUniqueId());
        if (d == null) { sender.sendMessage(fmt.error("You are not in a district.")); return true; }
        var list = jobs.getActiveJobs(d.getId());
        sender.sendMessage(fmt.header("District Jobs: " + d.getName() + " (" + list.size() + ")"));
        for (var job : list) sendJobLine(sender, job);
        sender.sendMessage(fmt.info("Use &e/district job accept <id>&7."));
        return true;
    }

    private boolean handleJob(CommandSender sender, String[] args) {
        DistrictJobService jobs = getJobService(sender);
        if (jobs == null) return true;
        if (args.length < 2) {
            sender.sendMessage(fmt.error("Usage: /district job <create|list|accept|deliver|submit|approve|deny>"));
            return true;
        }
        return switch (args[1].toLowerCase(Locale.ROOT)) {
            case "create" -> handleJobCreate(sender, args, jobs);
            case "list" -> handleJobList(sender, jobs);
            case "accept" -> handleJobAccept(sender, args, jobs);
            case "deliver" -> handleJobDeliver(sender, args, jobs);
            case "submit" -> handleJobSubmit(sender, args, jobs);
            case "approve" -> handleJobApprove(sender, args, jobs);
            case "deny" -> handleJobDeny(sender, args, jobs);
            default -> { sender.sendMessage(fmt.error("Unknown job action.")); yield true; }
        };
    }

    private boolean handleJobCreate(CommandSender sender, String[] args, DistrictJobService jobs) {
        if (!(sender instanceof Player player)) return true;
        if (args.length < 9) {
            sender.sendMessage(fmt.error("Usage: /district job create <type> <reward> <hours> <item|none> <amount> <manual:true|false> <title...>"));
            sender.sendMessage(fmt.info("Example: /district job create DELIVERY 100 24 stone 32 false Bring stone"));
            return true;
        }
        var d = districtService.getPlayerDistrict(player.getUniqueId());
        if (d == null) { sender.sendMessage(fmt.error("You are not in a district.")); return true; }
        DistrictJobData.JobType type = parseJobType(args[2]);
        if (type == null) { sender.sendMessage(fmt.error("Invalid job type.")); return true; }
        long reward = parseLong(args[3]);
        long hours = parseLong(args[4]);
        String item = args[5].equalsIgnoreCase("none") ? null : args[5];
        int amount = parseInt(args[6]);
        boolean manual = Boolean.parseBoolean(args[7]);
        String title = String.join(" ", Arrays.copyOfRange(args, 8, args.length));
        if (item != null && jobs.parseItem(item) == null) {
            sender.sendMessage(fmt.error("Invalid item name: " + item + ". Try stone, oak_log, iron_ingot, or minecraft:stone."));
            return true;
        }
        var job = jobs.createJob(player, d, type, title, title, reward, hours, item, amount, null, null, null, manual);
        if (job != null) sender.sendMessage(fmt.success("Created active district job #" + job.getId() + " with treasury escrow."));
        return true;
    }

    private boolean handleJobList(CommandSender sender, DistrictJobService jobs) {
        if (!(sender instanceof Player player)) return true;
        var d = districtService.getPlayerDistrict(player.getUniqueId());
        if (d == null) { sender.sendMessage(fmt.error("You are not in a district.")); return true; }
        sender.sendMessage(fmt.header("Active District Jobs"));
        jobs.getActiveJobs(d.getId()).forEach(job -> sendJobLine(sender, job));
        sender.sendMessage(fmt.header("My Accepted Jobs"));
        jobs.getClaimsFor(player).forEach(claim -> {
            var job = jobs.getJob(claim.getJobId());
            if (job != null) sender.sendMessage(fmt.info("&eClaim #" + claim.getId() + " &7job #" + job.getId() + " &8| &f" + job.getTitle() + " &8| &7" + claim.getStatus()));
        });
        if (districtService.canApproveDistrictJob(player.getUniqueId(), d)) {
            sender.sendMessage(fmt.header("Submitted Jobs"));
            jobs.getSubmittedClaims(d.getId()).forEach(claim -> {
                var job = jobs.getJob(claim.getJobId());
                if (job != null) sender.sendMessage(fmt.info("&eClaim #" + claim.getId() + " &7job #" + job.getId() + " &8| &f" + job.getTitle() + " &8| player " + claim.getPlayerUuid()));
            });
        }
        return true;
    }

    private boolean handleJobAccept(CommandSender sender, String[] args, DistrictJobService jobs) {
        if (!(sender instanceof Player player)) return true;
        if (args.length < 3) { sender.sendMessage(fmt.error("Usage: /district job accept <id>")); return true; }
        sender.sendMessage(jobs.acceptJob(player, parseInt(args[2])) ? fmt.success("Job accepted.") : fmt.error("Cannot accept job."));
        return true;
    }
    private boolean handleJobDeliver(CommandSender sender, String[] args, DistrictJobService jobs) {
        if (!(sender instanceof Player player)) return true;
        if (args.length < 3) { sender.sendMessage(fmt.error("Usage: /district job deliver <id>")); return true; }
        sender.sendMessage(jobs.deliverJob(player, parseInt(args[2])) ? fmt.success("Delivery complete. Payout pending.") : fmt.error("Cannot deliver. Check required item and amount."));
        return true;
    }
    private boolean handleJobSubmit(CommandSender sender, String[] args, DistrictJobService jobs) {
        if (!(sender instanceof Player player)) return true;
        if (args.length < 3) { sender.sendMessage(fmt.error("Usage: /district job submit <id>")); return true; }
        sender.sendMessage(jobs.submitJob(player, parseInt(args[2])) ? fmt.success("Job submitted for approval.") : fmt.error("Cannot submit job."));
        return true;
    }
    private boolean handleJobApprove(CommandSender sender, String[] args, DistrictJobService jobs) {
        if (!(sender instanceof Player player)) return true;
        if (args.length < 3) { sender.sendMessage(fmt.error("Usage: /district job approve <claimId>")); return true; }
        sender.sendMessage(jobs.approveClaim(player, parseInt(args[2])) ? fmt.success("Claim approved. Payout locker entry created.") : fmt.error("Cannot approve claim."));
        return true;
    }
    private boolean handleJobDeny(CommandSender sender, String[] args, DistrictJobService jobs) {
        if (!(sender instanceof Player player)) return true;
        if (args.length < 4) { sender.sendMessage(fmt.error("Usage: /district job deny <claimId> <reason>")); return true; }
        sender.sendMessage(jobs.denyClaim(player, parseInt(args[2]), String.join(" ", Arrays.copyOfRange(args, 3, args.length))) ? fmt.success("Claim denied.") : fmt.error("Cannot deny claim."));
        return true;
    }

    private boolean handleDisband(CommandSender sender) {
        if (!(sender instanceof Player player)) return true;
        var d = districtService.getPlayerDistrict(player.getUniqueId());
        if (d == null) { sender.sendMessage(fmt.error("You are not in a district.")); return true; }
        if (districtService.disband(d.getId(), player.getUniqueId())) {
            player.sendMessage(fmt.success("District disbanded."));
        } else {
            player.sendMessage(fmt.error("Only the mayor or an admin can disband the district."));
        }
        return true;
    }

    private boolean handleApplications(CommandSender sender) {
        if (!sender.hasPermission("vs.district.admin")) {
            sender.sendMessage(fmt.permissionDenied());
            return true;
        }
        var apps = districtService.getApplications();
        sender.sendMessage(fmt.header("Pending Applications (" + apps.size() + ")"));
        if (apps.isEmpty()) {
            sender.sendMessage(fmt.info("No pending applications."));
        } else {
            for (var d : apps) {
                @SuppressWarnings("deprecation")
                var founder = Bukkit.getOfflinePlayer(d.getFounderUuid()).getName();
                sender.sendMessage(fmt.info(
                    "&e#" + d.getId() + " &f" + d.getName() +
                    " &8| Founder: &e" + founder +
                    " &8| Use &e/district approve " + d.getId() + " &8or &e/district reject " + d.getId()
                ));
            }
        }
        return true;
    }

    private void showDistrictInfo(CommandSender sender, DistrictData.District d) {
        sender.sendMessage(fmt.header("District: " + d.getName()));
        sender.sendMessage(fmt.info("ID: &e#" + d.getId() + " &8| Status: &e" + d.getStatus()));
        sender.sendMessage(fmt.info("Treasury: &6" + fmt.formatMoney(d.getTreasuryBalance(),
            plugin.getConfigManager().getCurrencyName(),
            plugin.getConfigManager().getCurrencyNamePlural())));
        sender.sendMessage(fmt.info("Members (" + d.getMemberCount() + "):"));
        for (var entry : d.getRoles().entrySet()) {
            @SuppressWarnings("deprecation")
            String name = Bukkit.getOfflinePlayer(entry.getKey()).getName();
            if (name == null) name = entry.getKey().toString().substring(0, 8);
            sender.sendMessage(fmt.info("  &e" + entry.getValue().name() + " &8- &7" + name));
        }
        if (!d.getLaws().isEmpty()) {
            sender.sendMessage(fmt.info("Laws:"));
            d.getLaws().forEach((law, enabled) -> {
                sender.sendMessage(fmt.info("  " + (enabled ? "&a✔" : "&c✘") + " &7" + law));
            });
        }
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(fmt.header("District Commands"));
        sender.sendMessage(fmt.info("/district apply <name> &8- Found a district"));
        sender.sendMessage(fmt.info("/district info [id] &8- District info"));
        sender.sendMessage(fmt.info("/district invite <player> &8- Invite to your district"));
        sender.sendMessage(fmt.info("/district kick <player> &8- Kick member"));
        sender.sendMessage(fmt.info("/district role list <player> &8- List district roles"));
        sender.sendMessage(fmt.info("/district role set <player> <role> &8- Add district role"));
        sender.sendMessage(fmt.info("/district role remove <player> <role> &8- Remove district role"));
        sender.sendMessage(fmt.info("/district permissions &8- Show your district permissions"));
        sender.sendMessage(fmt.info("/district members &8- List district members"));
        sender.sendMessage(fmt.info("/district deposit <amount> &8- Deposit to treasury"));
        sender.sendMessage(fmt.info("/district withdraw <amount> &8- Withdraw from treasury"));
        sender.sendMessage(fmt.info("/district laws &8- Show active laws"));
        sender.sendMessage(fmt.info("/district laws pending &8- Show pending law changes"));
        sender.sendMessage(fmt.info("/district law propose <law> <true|false> &8- Propose daily law change"));
        sender.sendMessage(fmt.info("/district jobs &8- Show active jobs"));
        sender.sendMessage(fmt.info("/district job create|list|accept|deliver|submit|approve|deny &8- District jobs"));
        sender.sendMessage(fmt.info("/district list &8- All districts"));
        sender.sendMessage(fmt.info("/district disband &8- Disband your district"));
        if (sender.hasPermission("vs.district.admin")) {
            sender.sendMessage(fmt.info("/district approve <id> &8- Approve application"));
            sender.sendMessage(fmt.info("/district reject <id> <reason> &8- Reject application"));
            sender.sendMessage(fmt.info("/district applications &8- Pending applications"));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("apply", "current", "approve", "reject", "info", "invite", "kick",
                "role", "permissions", "members", "deposit", "withdraw", "laws", "law", "jobs", "job", "list", "disband", "applications")
                .stream().filter(a -> a.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("job")) {
            return List.of("create", "list", "accept", "deliver", "submit", "approve", "deny").stream().filter(a -> a.startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("job") && args[1].equalsIgnoreCase("create")) {
            return Arrays.stream(DistrictJobData.JobType.values()).map(Enum::name).filter(a -> a.startsWith(args[2].toUpperCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("laws")) {
            return List.of("pending").stream().filter(a -> a.startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("law")) {
            return List.of("propose").stream().filter(a -> a.startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("law") && args[1].equalsIgnoreCase("propose")) {
            return Arrays.stream(DistrictData.LawKey.values())
                .map(Enum::name).filter(r -> r.startsWith(args[2].toUpperCase())).toList();
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("law") && args[1].equalsIgnoreCase("propose")) {
            return List.of("true", "false").stream().filter(v -> v.startsWith(args[3].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("role")) {
            return List.of("list", "set", "remove").stream()
                .filter(a -> a.startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("role")
            && (args[1].equalsIgnoreCase("set") || args[1].equalsIgnoreCase("remove"))) {
            return Arrays.stream(DistrictData.DistrictRole.values())
                .map(Enum::name).filter(r -> r.startsWith(args[3].toUpperCase())).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("invite") || args[0].equalsIgnoreCase("kick"))) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("role")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                .filter(n -> n.toLowerCase().startsWith(args[2].toLowerCase())).toList();
        }
        return List.of();
    }

    private UUID findDistrictMemberUuid(DistrictData.District district, String targetName) {
        for (UUID memberUuid : district.getMembers()) {
            @SuppressWarnings("deprecation")
            String name = Bukkit.getOfflinePlayer(memberUuid).getName();
            if (targetName.equalsIgnoreCase(name)) {
                return memberUuid;
            }
        }
        return null;
    }

    private DistrictData.DistrictRole parseRole(String raw) {
        if (raw == null) return null;
        String normalized = raw.toUpperCase(Locale.ROOT);
        if (normalized.equals("CITIZEN")) normalized = "MEMBER";
        if (normalized.equals("COUNCIL")) normalized = "CO_MAYOR";
        try {
            return DistrictData.DistrictRole.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private DistrictData.LawKey parseLaw(String raw) {
        if (raw == null) return null;
        try {
            return DistrictData.LawKey.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private DistrictJobData.JobType parseJobType(String raw) {
        try { return DistrictJobData.JobType.valueOf(raw.toUpperCase(Locale.ROOT)); } catch (Exception ignored) { return null; }
    }

    private DistrictJobService getJobService(CommandSender sender) {
        try { return plugin.getServiceRegistry().get(DistrictJobService.class); }
        catch (RuntimeException e) { sender.sendMessage(fmt.error("District job service is not available yet.")); return null; }
    }

    private void sendJobLine(CommandSender sender, DistrictJobData.Job job) {
        sender.sendMessage(fmt.info("&e#" + job.getId() + " &7" + job.getType() + " &8| &f" + job.getTitle()
            + " &8| &6" + job.getReward() + " &8| &7" + job.getStatus()
            + (job.getRequiredItem() != null ? " &8| &7" + job.getRequiredAmount() + "x " + job.getRequiredItem() : "")));
    }

    private void sendRoleList(CommandSender sender) {
        sender.sendMessage(fmt.info("Roles: " + Arrays.stream(DistrictData.DistrictRole.values())
            .filter(role -> role != DistrictData.DistrictRole.VISITOR)
            .map(Enum::name)
            .collect(Collectors.joining(", "))));
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return -1; }
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return -1; }
    }
}

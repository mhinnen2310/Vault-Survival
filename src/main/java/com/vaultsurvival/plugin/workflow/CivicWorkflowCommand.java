package com.vaultsurvival.plugin.workflow;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.dialogs.DialogMenuItem;
import com.vaultsurvival.plugin.dialogs.DialogService;
import com.vaultsurvival.plugin.districts.DistrictData;
import com.vaultsurvival.plugin.districts.DistrictService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Command front-end for persistent civic workflows. Dialogs call this command too. */
public final class CivicWorkflowCommand implements CommandExecutor, TabCompleter {
    private final VaultSurvivalPlugin plugin;
    private final CivicWorkflowService service;

    public CivicWorkflowCommand(VaultSurvivalPlugin plugin, CivicWorkflowService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getMessageFormatter().error("This command is available in game."));
            return true;
        }
        if (args.length == 0) {
            player.sendMessage(plugin.getMessageFormatter().info("/civic <preferences|profile|join|joins|report|reports|diplomacy|jobs|support|guide|rail>"));
            return true;
        }
        try {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "preferences", "preference", "prefs" -> preferences(player, args);
                case "profile" -> profile(player, args);
                case "join" -> join(player, args);
                case "joins", "joinrequests" -> joins(player, args);
                case "report" -> report(player, args);
                case "reports" -> reports(player, args);
                case "diplomacy", "diplomatic" -> diplomacy(player, args);
                case "jobs", "job" -> jobs(player, args);
                case "support" -> support(player, args);
                case "guide", "guides" -> guide(player, args);
                case "rail" -> rail(player, args);
                default -> { player.sendMessage(plugin.getMessageFormatter().error("Unknown civic workflow.")); yield true; }
            };
        } catch (IllegalArgumentException | IllegalStateException expected) {
            player.sendMessage(plugin.getMessageFormatter().error(expected.getMessage()));
            return true;
        }
    }

    private boolean preferences(Player player, String[] args) {
        CivicWorkflowService.Preferences value;
        if (args.length >= 2) {
            value = service.setPreference(player.getUniqueId(), args[1], args.length >= 3 ? args[2] : "next");
            player.sendMessage(plugin.getMessageFormatter().success("Preference updated."));
        } else value = service.preferences(player.getUniqueId());
        List<DialogMenuItem> items = List.of(
            DialogMenuItem.item("Notifications: " + value.notifications(), "ALL, IMPORTANT, or OFF. Click to cycle.", "civic preferences notifications next", null, Material.BELL),
            DialogMenuItem.item("Menu Style: " + value.menuStyle(), "AUTO, NATIVE, or COMPACT. Click to cycle.", "civic preferences menu next", null, Material.COMPARATOR),
            DialogMenuItem.item("Privacy: " + value.privacy(), "PUBLIC, FRIENDS, or PRIVATE. Click to cycle.", "civic preferences privacy next", null, Material.ENDER_EYE),
            back("settings"));
        open(player, "Player Preferences", "These settings are stored per player and apply across restarts.", items);
        return true;
    }

    private boolean profile(Player viewer, String[] args) {
        String targetName = args.length > 1 ? args[1] : viewer.getName();
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        if (!target.hasPlayedBefore() && !target.isOnline() && !target.getUniqueId().equals(viewer.getUniqueId())) {
            throw new IllegalArgumentException("Player not found.");
        }
        if (!service.canViewProfile(viewer.getUniqueId(), target.getUniqueId())) {
            throw new IllegalStateException("That player's profile is limited by their privacy setting.");
        }
        var access = plugin.getServiceRegistry().get(com.vaultsurvival.plugin.access.AccessService.class);
        DistrictData.District district = districts().getPlayerDistrict(target.getUniqueId());
        String name = target.getName() == null ? target.getUniqueId().toString() : target.getName();
        String body = "Rank: " + access.getPrimaryGroup(target.getUniqueId()) + "\nDistrict: "
            + (district == null ? "None" : district.getName()) + "\nStatus: " + (target.isOnline() ? "Online" : "Offline")
            + "\nPrivacy: " + service.preferences(target.getUniqueId()).privacy();
        open(viewer, "Player Profile: " + name, body, List.of(
            district == null ? info("No District", "This player is not a district member.", Material.MAP)
                : DialogMenuItem.item("District", "Open public district information.", "district info " + district.getId(), null, Material.MAP),
            back("settings")));
        return true;
    }

    private boolean join(Player player, String[] args) {
        if (args.length < 2) throw new IllegalArgumentException("Usage: /civic join <district id|name> [message]");
        DistrictData.District district = findDistrict(args[1]);
        if (district == null) throw new IllegalArgumentException("District not found.");
        String message = args.length > 2 ? join(args, 2) : "Requested from the current-area menu.";
        CivicWorkflowService.JoinRequest request = service.requestJoin(player, district.getId(), message);
        player.sendMessage(plugin.getMessageFormatter().success("Join request #" + request.id() + " sent to " + district.getName() + "."));
        return true;
    }

    private boolean joins(Player player, String[] args) {
        DistrictData.District district = districts().getPlayerDistrict(player.getUniqueId());
        if (district == null || !districts().canManageRoles(player.getUniqueId(), district)) {
            throw new IllegalStateException("Council membership is required to review join requests.");
        }
        if (args.length >= 3 && (args[1].equalsIgnoreCase("approve") || args[1].equalsIgnoreCase("deny"))) {
            int id = integer(args[2], "request id");
            boolean success = service.resolveJoinRequest(player, id, args[1].equalsIgnoreCase("approve"));
            player.sendMessage(success ? plugin.getMessageFormatter().success("Join request updated.")
                : plugin.getMessageFormatter().error("Open join request not found or could not be handled."));
        }
        List<CivicWorkflowService.JoinRequest> requests = service.joinRequests(district.getId(), false);
        List<DialogMenuItem> items = new ArrayList<>();
        for (CivicWorkflowService.JoinRequest request : requests) {
            items.add(DialogMenuItem.adminItem("Approve #" + request.id() + " - " + request.playerName(),
                empty(request.message(), "No message") + " | " + age(request.createdAt()),
                "civic joins approve " + request.id(), null, Material.LIME_DYE));
            items.add(DialogMenuItem.adminItem("Deny #" + request.id(), "Deny " + request.playerName() + "'s request.",
                "civic joins deny " + request.id(), null, Material.RED_DYE));
        }
        if (items.isEmpty()) items.add(info("No Open Requests", "No players are waiting for a decision.", Material.PAPER));
        items.add(back("district"));
        open(player, district.getName() + " Join Requests", requests.size() + " open request(s).", items);
        return true;
    }

    private boolean report(Player player, String[] args) {
        if (args.length < 4) throw new IllegalArgumentException("Usage: /civic report <category> <player|none> <details>");
        CivicWorkflowService.Report report = service.createReport(player, args[1], args[2], join(args, 3));
        player.sendMessage(plugin.getMessageFormatter().success("Report #" + report.id() + " submitted. Staff can now claim and resolve it."));
        return true;
    }

    private boolean reports(Player player, String[] args) {
        requireStaff(player);
        if (args.length >= 3) {
            int id = integer(args[2], "report id");
            boolean success = switch (args[1].toLowerCase(Locale.ROOT)) {
                case "claim" -> service.claimReport(player, id);
                case "resolve" -> service.resolveReport(player, id, false, args.length > 3 ? join(args, 3) : "Resolved by staff");
                case "dismiss" -> service.resolveReport(player, id, true, args.length > 3 ? join(args, 3) : "Dismissed by staff");
                default -> false;
            };
            player.sendMessage(success ? plugin.getMessageFormatter().success("Report queue updated.")
                : plugin.getMessageFormatter().error("Report was already handled or not found."));
        }
        String category = args.length >= 2 && !List.of("claim", "resolve", "dismiss", "list").contains(args[1].toLowerCase(Locale.ROOT))
            ? args[1] : "ALL";
        List<CivicWorkflowService.Report> reports = service.reports(category, false);
        List<DialogMenuItem> items = new ArrayList<>();
        for (CivicWorkflowService.Report report : reports) {
            String subject = report.subjectName() == null ? "No named subject" : report.subjectName();
            items.add(DialogMenuItem.adminItem("#" + report.id() + " " + report.category(),
                report.reporterName() + " -> " + subject + " | " + shortText(report.details(), 95),
                report.status().equals("OPEN") ? "civic reports claim " + report.id() : "civic reports resolve " + report.id() + " Reviewed",
                "vs.staffmode.use", report.category().equals("POLICE_ABUSE") ? Material.IRON_BARS : Material.PAPER));
        }
        if (items.isEmpty()) items.add(info("Queue Clear", "No open reports match this filter.", Material.LIME_DYE));
        items.add(DialogMenuItem.adminItem("Resolve by ID", "Enter report id and resolution note.", "vsmenu input report_resolve", "vs.staffmode.use", Material.EMERALD));
        items.add(DialogMenuItem.adminItem("Dismiss by ID", "Enter report id and dismissal note.", "vsmenu input report_dismiss", "vs.staffmode.use", Material.BARRIER));
        items.add(back("staff"));
        open(player, category.equals("ALL") ? "Staff Reports" : category + " Reports", reports.size() + " actionable report(s).", items);
        return true;
    }

    private boolean diplomacy(Player player, String[] args) {
        DistrictData.District district = districts().getPlayerDistrict(player.getUniqueId());
        if (district == null) throw new IllegalStateException("Join a district to use diplomacy.");
        if (args.length >= 3 && !args[1].equalsIgnoreCase("list")) {
            DistrictData.District target = findDistrict(args[2]);
            if (target == null) throw new IllegalArgumentException("Target district not found.");
            boolean success = service.changeDiplomacy(player, args[1], target.getId());
            player.sendMessage(success ? plugin.getMessageFormatter().success("Diplomacy updated with " + target.getName() + ".")
                : plugin.getMessageFormatter().error("That diplomacy transition is not allowed."));
        }
        List<CivicWorkflowService.Diplomacy> relations = service.diplomacyFor(district.getId());
        List<DialogMenuItem> items = new ArrayList<>();
        for (CivicWorkflowService.Diplomacy relation : relations) {
            int otherId = relation.districtA() == district.getId() ? relation.districtB() : relation.districtA();
            DistrictData.District other = districts().getDistrict(otherId);
            String name = other == null ? "District #" + otherId : other.getName();
            items.add(info(name, relation.relation() + " | changed " + age(relation.updatedAt()),
                relation.relation().equals("ALLIED") ? Material.LIME_BANNER : relation.relation().equals("HOSTILE") ? Material.RED_BANNER : Material.WHITE_BANNER));
            if (relation.relation().equals("PENDING_ALLIANCE") && relation.proposerDistrict() != null && relation.proposerDistrict() != district.getId()) {
                items.add(DialogMenuItem.adminItem("Accept " + name, "Accept the pending alliance.", "civic diplomacy accept " + otherId, null, Material.EMERALD));
            }
        }
        if (relations.isEmpty()) items.add(info("No Relations", "All districts currently default to neutral.", Material.WHITE_BANNER));
        items.add(DialogMenuItem.item("Request Alliance", "Enter a district id or exact name.", "vsmenu input diplomacy_request", null, Material.LIME_BANNER));
        items.add(DialogMenuItem.item("Set Neutral", "End an alliance or hostility.", "vsmenu input diplomacy_neutral", null, Material.WHITE_BANNER));
        items.add(DialogMenuItem.item("Declare Hostile", "Mark a district as hostile.", "vsmenu input diplomacy_hostile", null, Material.RED_BANNER));
        items.add(DialogMenuItem.item("Ally Chat", "Talk to online members of allied districts.", "chat ally", null, Material.WRITABLE_BOOK));
        items.add(back("district"));
        open(player, district.getName() + " Diplomacy", relations.size() + " recorded relation(s).", items);
        return true;
    }

    private boolean jobs(Player player, String[] args) {
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "history";
        DistrictData.District district = districts().getPlayerDistrict(player.getUniqueId());
        if (action.equals("dispute")) {
            if (args.length < 4) throw new IllegalArgumentException("Usage: /civic jobs dispute <claim id> <reason>");
            int id = service.openJobDispute(player, integer(args[2], "claim id"), join(args, 3));
            player.sendMessage(plugin.getMessageFormatter().success("District job dispute #" + id + " opened."));
        } else if (action.equals("resolve")) {
            if (args.length < 4) throw new IllegalArgumentException("Usage: /civic jobs resolve <dispute id> <approve|deny> [note]");
            boolean success = service.resolveJobDispute(player, integer(args[2], "dispute id"), args[3].equalsIgnoreCase("approve"), args.length > 4 ? join(args, 4) : "Reviewed");
            player.sendMessage(success ? plugin.getMessageFormatter().success("Job dispute resolved.") : plugin.getMessageFormatter().error("Dispute could not be resolved."));
        }
        if (action.equals("disputes") || action.equals("resolve")) {
            List<CivicWorkflowService.JobDispute> disputes = service.jobDisputes(district == null || player.hasPermission("vs.admin") ? null : district.getId(), false);
            List<DialogMenuItem> items = new ArrayList<>();
            for (var dispute : disputes) items.add(DialogMenuItem.adminItem("Dispute #" + dispute.id() + " / Claim #" + dispute.claimId(),
                shortText(dispute.reason(), 110), "vsmenu input job_dispute_resolve", null, Material.REDSTONE_TORCH));
            if (items.isEmpty()) items.add(info("No Open Disputes", "The district-job dispute queue is clear.", Material.LIME_DYE));
            items.add(back("district.jobs"));
            open(player, "District Job Disputes", disputes.size() + " open dispute(s).", items);
            return true;
        }
        UUID playerFilter = district != null && districts().canApproveDistrictJob(player.getUniqueId(), district) ? null : player.getUniqueId();
        List<CivicWorkflowService.JobHistory> history = service.jobHistory(district == null ? null : district.getId(), playerFilter, 50);
        List<DialogMenuItem> items = new ArrayList<>();
        for (var row : history) items.add(info("Claim #" + row.claimId() + " - " + row.title(),
            row.status() + " | reward " + row.reward() + " | " + age(row.createdAt()), row.status().equals("DENIED") ? Material.RED_DYE : Material.EMERALD));
        if (items.isEmpty()) items.add(info("No Completed Jobs", "Completed and reviewed job claims will appear here.", Material.BOOK));
        items.add(DialogMenuItem.item("Open Dispute", "Enter claim id and a reason.", "vsmenu input job_dispute", null, Material.REDSTONE_TORCH));
        items.add(DialogMenuItem.item("Dispute Queue", "Review open disputes for your district.", "civic jobs disputes", null, Material.PAPER));
        items.add(back("district.jobs"));
        open(player, "District Job History", history.size() + " completed/reviewed claim(s).", items);
        return true;
    }

    private boolean support(Player player, String[] args) {
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "list";
        if (action.equals("request")) {
            if (args.length < 4) throw new IllegalArgumentException("Usage: /civic support request <project id|all> <details>");
            Integer project = args[2].equalsIgnoreCase("all") ? null : integer(args[2], "project id");
            int id = service.requestKingdomSupport(player, project, join(args, 3));
            player.sendMessage(plugin.getMessageFormatter().success("Kingdom Support request #" + id + " created."));
        } else if (action.equals("assign") || action.equals("complete")) {
            requireStaff(player);
            if (args.length < 3) throw new IllegalArgumentException("A support request id is required.");
            int id = integer(args[2], "request id");
            boolean success = action.equals("assign") ? service.assignSupport(player, id)
                : service.completeSupport(player, id, args.length > 3 ? join(args, 3) : "Completed by staff");
            player.sendMessage(success ? plugin.getMessageFormatter().success("Support request updated.") : plugin.getMessageFormatter().error("Support request could not be updated."));
        }
        DistrictData.District district = districts().getPlayerDistrict(player.getUniqueId());
        boolean staff = isStaff(player);
        List<CivicWorkflowService.SupportRequest> requests = service.supportRequests(staff ? null : district == null ? -1 : district.getId(), false);
        List<DialogMenuItem> items = new ArrayList<>();
        for (var request : requests) {
            items.add(staff && request.status().equals("OPEN")
                ? DialogMenuItem.adminItem("Assign #" + request.id() + " / District #" + request.districtId(), shortText(request.details(), 100), "civic support assign " + request.id(), "vs.staffmode.use", Material.WRITABLE_BOOK)
                : info("#" + request.id() + " - " + request.status(), shortText(request.details(), 110), Material.WRITABLE_BOOK));
            if (staff && request.status().equals("ASSIGNED")) items.add(DialogMenuItem.adminItem("Complete #" + request.id(), "Mark assigned support work completed.", "civic support complete " + request.id() + " Completed", "vs.staffmode.use", Material.EMERALD));
        }
        if (items.isEmpty()) items.add(info("No Open Requests", "There are no open Kingdom Support requests.", Material.LIME_DYE));
        if (!staff) items.add(DialogMenuItem.item("Request Support", "Enter project id/all and details.", "vsmenu input support_request", null, Material.BELL));
        items.add(back(staff ? "staff.districts" : "district.development"));
        open(player, "Kingdom Support", requests.size() + " active request(s).", items);
        return true;
    }

    private boolean guide(Player player, String[] args) {
        if (args.length < 2) throw new IllegalArgumentException("Choose vaults, districts, or auction.");
        String type = args[1].toLowerCase(Locale.ROOT);
        String title; String body; List<DialogMenuItem> items;
        switch (type) {
            case "vault", "vaults" -> {
                title = "Vault Guide";
                body = "Place a vault, deposit physical cash, grant access carefully, and keep repair materials ready. Breaches can steal only part of protected value; lockdown and repair state remain visible in /vault info.";
                items = List.of(DialogMenuItem.item("My Vaults", "List vaults you own or can access.", "vault list", "vs.vault.use", Material.BARREL), DialogMenuItem.item("Inspect Target", "Show capacity, access, breach and repair state.", "vault info", "vs.vault.use", Material.SPYGLASS), back("guides"));
            }
            case "district", "districts" -> {
                title = "District Guide";
                body = "Districts use exact block-border claims, role-based governance, physical treasury cash, daily law activation, development levels, jobs, diplomacy, stations, and NPC unlocks. Applications and expansion are validated before confirmation.";
                items = List.of(DialogMenuItem.item("District List", "Browse official districts.", "district list", null, Material.MAP), DialogMenuItem.item("My District", "Open role-specific district tools.", "vsmenu district", null, Material.PLAYER_HEAD), back("guides"));
            }
            case "auction", "auctionhall", "ah" -> {
                title = "Auction Hall Guide";
                body = "Enter the physical Auction Hall, hold the item you want to sell, and create a listing. Buyers pay with physical cash. Sold-item proceeds stay safely in the Auction Locker until collected.";
                items = List.of(DialogMenuItem.item("Browse Listings", "View active listings.", "ah listings", "vs.market.buy", Material.GOLD_INGOT), DialogMenuItem.item("Sell Held Item", "Enter price, category, and duration.", "vsmenu input ah_sell", "vs.market.sell", Material.WRITABLE_BOOK), DialogMenuItem.item("Collect Earnings", "Collect Auction Locker proceeds.", "ah collect", "vs.market.sell", Material.CHEST), back("guides"));
            }
            default -> throw new IllegalArgumentException("Choose vaults, districts, or auction.");
        }
        open(player, title, body, items);
        return true;
    }

    private boolean rail(Player player, String[] args) {
        requireStaff(player);
        String mode = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "travel";
        if (!mode.equals("revenue") && !mode.equals("travel")) throw new IllegalArgumentException("Usage: /civic rail <revenue|travel>");
        List<DialogMenuItem> items = new ArrayList<>();
        String sql = mode.equals("revenue")
            ? "SELECT route_id,from_station,to_station,COUNT(*) trips,SUM(ticket_price) total,MAX(created_at) latest FROM rail_journey_log WHERE event='TICKET_PURCHASED' GROUP BY route_id,from_station,to_station ORDER BY latest DESC LIMIT 50"
            : "SELECT id,player_name,route_id,from_station,to_station,ticket_price,event,created_at FROM rail_journey_log ORDER BY created_at DESC LIMIT 50";
        try (Connection connection = plugin.getDatabase().getConnection(); PreparedStatement statement = connection.prepareStatement(sql); ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                if (mode.equals("revenue")) items.add(info("Route #" + result.getInt("route_id") + " - " + result.getString("from_station") + " -> " + result.getString("to_station"),
                    result.getInt("trips") + " ticket(s) | gross " + result.getLong("total") + " | " + age(result.getLong("latest")), Material.GOLD_NUGGET));
                else items.add(info("#" + result.getInt("id") + " " + result.getString("player_name") + " / Route #" + result.getInt("route_id"),
                    result.getString("event") + " | " + result.getString("from_station") + " -> " + result.getString("to_station") + " | " + age(result.getLong("created_at")), Material.MINECART));
            }
        } catch (Exception error) {
            throw new IllegalStateException("Could not load rail logs.");
        }
        if (items.isEmpty()) items.add(info("No Rail Events", "Rail travel will appear here after the first ticket is purchased.", Material.RAIL));
        items.add(back("staff.rail"));
        open(player, mode.equals("revenue") ? "Rail Revenue Log" : "Rail Travel Log", items.size() - 1 + " recent row(s).", items);
        return true;
    }

    private void requireStaff(Player player) {
        if (!isStaff(player)) throw new IllegalStateException("Activate staffmode to use this workflow.");
    }

    private boolean isStaff(Player player) {
        return plugin.isStaffModeActive(player.getUniqueId())
            && (player.hasPermission("vs.staffmode.use") || player.hasPermission("vs.admin"));
    }

    private DistrictData.District findDistrict(String value) {
        try {
            DistrictData.District byId = districts().getDistrict(Integer.parseInt(value));
            if (byId != null) return byId;
        } catch (NumberFormatException ignored) { }
        return districts().getAllDistricts().stream().filter(row -> row.getName().equalsIgnoreCase(value)).findFirst().orElse(null);
    }

    private DistrictService districts() { return plugin.getServiceRegistry().get(DistrictService.class); }
    private void open(Player player, String title, String body, List<DialogMenuItem> items) {
        try { plugin.getServiceRegistry().get(DialogService.class).openResult(player, title, body, items); }
        catch (RuntimeException unavailable) { player.sendMessage(plugin.getMessageFormatter().info(title + ": " + body)); }
    }
    private DialogMenuItem info(String label, String description, Material material) {
        return DialogMenuItem.locked(label, description, description, material);
    }
    private DialogMenuItem back(String route) { return DialogMenuItem.item("Back", "Return to the previous menu.", "vsmenu " + route, null, Material.ARROW); }
    private int integer(String value, String label) { try { return Integer.parseInt(value); } catch (NumberFormatException error) { throw new IllegalArgumentException("Invalid " + label + "."); } }
    private String join(String[] args, int start) { return String.join(" ", Arrays.copyOfRange(args, start, args.length)).trim(); }
    private String empty(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private String shortText(String value, int max) { String clean = empty(value, "No details").replace('\n', ' '); return clean.length() <= max ? clean : clean.substring(0, max - 3) + "..."; }
    private String age(long timestamp) {
        if (timestamp <= 0) return "unknown time";
        long seconds = Math.max(0, Duration.ofMillis(System.currentTimeMillis() - timestamp).toSeconds());
        if (seconds < 60) return seconds + "s ago";
        if (seconds < 3600) return seconds / 60 + "m ago";
        if (seconds < 86400) return seconds / 3600 + "h ago";
        return seconds / 86400 + "d ago";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return filter(List.of("preferences", "profile", "join", "joins", "report", "reports", "diplomacy", "jobs", "support", "guide", "rail"), args[0]);
        if (args.length == 2) return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "preferences", "preference", "prefs" -> filter(List.of("notifications", "menu", "privacy"), args[1]);
            case "joins" -> filter(List.of("approve", "deny"), args[1]);
            case "reports" -> filter(List.of("ALL", "POLICE_ABUSE", "THEFT", "GRIEFING", "HARASSMENT", "claim", "resolve", "dismiss"), args[1]);
            case "diplomacy" -> filter(List.of("list", "request", "accept", "deny", "neutral", "hostile"), args[1]);
            case "jobs", "job" -> filter(List.of("history", "dispute", "disputes", "resolve"), args[1]);
            case "support" -> filter(List.of("list", "request", "assign", "complete"), args[1]);
            case "guide", "guides" -> filter(List.of("vaults", "districts", "auction"), args[1]);
            case "rail" -> filter(List.of("revenue", "travel"), args[1]);
            case "join" -> filter(districts().getAllDistricts().stream().map(d -> String.valueOf(d.getId())).toList(), args[1]);
            default -> List.of();
        };
        if (args.length == 3 && args[0].equalsIgnoreCase("preferences")) return filter(List.of("next", "ALL", "IMPORTANT", "OFF", "AUTO", "NATIVE", "COMPACT", "PUBLIC", "FRIENDS", "PRIVATE"), args[2]);
        return List.of();
    }

    private List<String> filter(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }
}

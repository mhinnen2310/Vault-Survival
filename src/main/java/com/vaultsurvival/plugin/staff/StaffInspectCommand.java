package com.vaultsurvival.plugin.staff;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.access.AccessService;
import com.vaultsurvival.plugin.area.CurrentAreaService;
import com.vaultsurvival.plugin.core.MessageFormatter;
import com.vaultsurvival.plugin.currency.CurrencyService;
import com.vaultsurvival.plugin.districts.DistrictData;
import com.vaultsurvival.plugin.districts.DistrictService;
import com.vaultsurvival.plugin.dialogs.DialogMenuItem;
import com.vaultsurvival.plugin.dialogs.DialogService;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Staff-only player search, profile and safe operational actions. */
public final class StaffInspectCommand implements CommandExecutor, TabCompleter, Listener {
    private static final int PAGE_SIZE = 8;
    private final VaultSurvivalPlugin plugin;
    private final MessageFormatter fmt;
    private final Set<UUID> frozen = new HashSet<>();

    public StaffInspectCommand(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.fmt = plugin.getMessageFormatter();
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("vs.staffinspect") && !sender.hasPermission("vs.admin")) {
            sender.sendMessage(fmt.permissionDenied()); return true;
        }
        if (args.length == 0) { usage(sender); return true; }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list", "online", "recent", "wanted", "frozen" -> { list(sender, args); yield true; }
            case "search" -> { search(sender, args); yield true; }
            case "freeze", "unfreeze" -> { freeze(sender, args); yield true; }
            case "tp", "bring", "spectate", "inventory", "ender", "cash", "vaults", "roles", "note", "punish" -> { action(sender, args); yield true; }
            default -> { profile(sender, args[0]); yield true; }
        };
    }

    private void list(CommandSender sender, String[] args) {
        String mode = args[0].toLowerCase(Locale.ROOT);
        int page = args.length > 1 ? parsePage(args[1]) : 1;
        List<OfflinePlayer> players = new ArrayList<>(List.of(Bukkit.getOfflinePlayers()));
        if (mode.equals("online")) players.removeIf(p -> !p.isOnline());
        if (mode.equals("frozen")) players.removeIf(p -> !frozen.contains(p.getUniqueId()));
        if (mode.equals("wanted")) players.removeIf(p -> !isWanted(p.getUniqueId()));
        players.sort(Comparator.comparing(OfflinePlayer::getLastPlayed).reversed());
        printPage(sender, players, page, mode.substring(0, 1).toUpperCase() + mode.substring(1) + " Players");
    }

    private void search(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage(fmt.error("Usage: /staffinspect search <name|uuid|district|rank> [page]")); return; }
        String query = args[1].toLowerCase(Locale.ROOT);
        int page = args.length > 2 ? parsePage(args[2]) : 1;
        List<OfflinePlayer> matches = new ArrayList<>();
        for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
            String name = player.getName() == null ? "" : player.getName().toLowerCase(Locale.ROOT);
            if (name.contains(query) || player.getUniqueId().toString().startsWith(query) || matchesDistrict(player.getUniqueId(), query) || matchesRank(player.getUniqueId(), query)) matches.add(player);
        }
        matches.sort(Comparator.comparing(OfflinePlayer::getLastPlayed).reversed());
        printPage(sender, matches, page, "Search: " + args[1]);
    }

    private void profile(CommandSender sender, String targetName) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        if (!target.hasPlayedBefore() && !target.isOnline()) { sender.sendMessage(fmt.error("Player not found.")); return; }
        UUID id = target.getUniqueId();
        Player online = target.getPlayer();
        if (sender instanceof Player staff && dialogs() != null) { openProfileDialog(staff, target); return; }
        sender.sendMessage(fmt.header("Player: " + safeName(target)));
        sender.sendMessage(fmt.info("UUID: &7" + id));
        sender.sendMessage(fmt.info("Status: " + (online == null ? "&7Offline" : "&aOnline") + (online == null ? "" : " &7| Ping: &e" + online.getPing() + " &7| " + online.getGameMode())));
        AccessService access = service(AccessService.class);
        if (access != null) sender.sendMessage(fmt.info("Rank: &e" + access.getPrimaryGroup(id)));
        DistrictService districts = service(DistrictService.class);
        DistrictData.District district = districts == null ? null : districts.getPlayerDistrict(id);
        sender.sendMessage(fmt.info("District: &e" + (district == null ? "None" : district.getName()) + (district == null ? "" : " &7| Roles: &f" + districts.getDistrictRoles(id, district))));
        CurrencyService currency = service(CurrencyService.class);
        if (currency != null) sender.sendMessage(fmt.info("Carried cash estimate: &6" + currency.getPlayerCashTotal(id)));
        sender.sendMessage(fmt.info("Wanted: " + (isWanted(id) ? "&cYes" : "&aNo") + " &7| Frozen: " + (frozen.contains(id) ? "&cYes" : "&aNo")));
        if (online != null) {
            CurrentAreaService area = service(CurrentAreaService.class);
            if (area != null) sender.sendMessage(fmt.info("Current area: &e" + area.resolve(online).areaType()));
        }
        sender.sendMessage(fmt.info("Actions: &e/staffinspect <tp|bring|spectate|freeze|inventory|ender|cash|vaults|roles> " + safeName(target)));
    }

    private void openProfileDialog(Player staff, OfflinePlayer target) {
        UUID id = target.getUniqueId(); Player online = target.getPlayer();
        AccessService access = service(AccessService.class); DistrictService districts = service(DistrictService.class);
        DistrictData.District district = districts == null ? null : districts.getPlayerDistrict(id);
        String body = "UUID: " + id + "\nRank: " + (access == null ? "Unknown" : access.getPrimaryGroup(id))
            + "\nDistrict: " + (district == null ? "None" : district.getName())
            + "\nStatus: " + (online == null ? "Offline" : "Online, ping " + online.getPing())
            + "\nWanted: " + (isWanted(id) ? "Yes" : "No") + " | Frozen: " + frozen.contains(id);
        List<DialogMenuItem> items = new ArrayList<>();
        if (online != null) {
            items.add(DialogMenuItem.adminItem("Teleport To", "Teleport to this player.", "staffinspect tp " + online.getName(), "vs.staffinspect", Material.ENDER_PEARL));
            items.add(DialogMenuItem.adminItem("Bring", "Bring this player to you.", "staffinspect bring " + online.getName(), "vs.staffinspect", Material.LEAD));
            items.add(DialogMenuItem.adminItem(frozen.contains(id) ? "Unfreeze" : "Freeze", "Toggle movement freeze.", "staffinspect " + (frozen.contains(id) ? "unfreeze " : "freeze ") + online.getName(), "vs.staffinspect.freeze", Material.ICE));
            items.add(DialogMenuItem.adminItem("Inventory", "Open player inventory.", "staffinspect inventory " + online.getName(), "vs.staffinspect", Material.CHEST));
        }
        items.add(DialogMenuItem.adminItem("Cash Trail", "Inspect physical cash.", "staffinspect cash " + safeName(target), "vs.staffinspect", Material.GOLD_NUGGET));
        items.add(DialogMenuItem.adminItem("District Roles", "Inspect district roles.", "staffinspect roles " + safeName(target), "vs.staffinspect", Material.NAME_TAG));
        items.add(DialogMenuItem.item("Back", "Return to player search.", "vsmenu players", null, Material.ARROW));
        dialogs().openResult(staff, "Player: " + safeName(target), body, items);
    }

    private void action(CommandSender sender, String[] args) {
        if (!(sender instanceof Player staff)) { sender.sendMessage(fmt.error("Players only.")); return; }
        if (args.length < 2) { usage(sender); return; }
        Player target = Bukkit.getPlayerExact(args[1]);
        String action = args[0].toLowerCase(Locale.ROOT);
        if (target == null) { sender.sendMessage(fmt.error("That action requires the player to be online.")); return; }
        if (!staff.hasPermission("vs.staffinspect." + action) && !staff.hasPermission("vs.admin")) { sender.sendMessage(fmt.permissionDenied()); return; }
        if ((action.equals("tp") || action.equals("bring") || action.equals("spectate"))
            && (args.length < 3 || !args[2].equalsIgnoreCase("confirmed"))) {
            DialogService dialogs = dialogs();
            if (dialogs != null) { dialogs.openConfirmation(staff, "Confirm " + action, "Confirm action for " + target.getName() + ".", "staffinspect " + action + " " + target.getName() + " confirmed", "players"); return; }
        }
        switch (action) {
            case "tp" -> staff.teleport(target.getLocation());
            case "bring" -> target.teleport(staff.getLocation());
            case "spectate" -> { staff.setGameMode(GameMode.SPECTATOR); staff.setSpectatorTarget(target); }
            case "inventory" -> staff.openInventory(target.getInventory());
            case "ender" -> staff.openInventory(target.getEnderChest());
            case "cash" -> staff.performCommand("cash inspect " + target.getName());
            case "vaults" -> staff.sendMessage(fmt.info("Vault audit: use &e/vsmenu vaults &7for " + target.getName() + "."));
            case "roles" -> staff.performCommand("district role list " + target.getName());
            case "note", "punish" -> { sender.sendMessage(fmt.warn("Use your moderation system for this action; the attempt was audited.")); }
            default -> { return; }
        }
        plugin.getAuditLogger().log(staff.getUniqueId(), staff.getName(), "STAFF_PLAYER_" + action.toUpperCase(Locale.ROOT), "PLAYER", target.getUniqueId().toString(), "target=" + target.getName());
        staff.sendMessage(fmt.success("Action completed for " + target.getName() + "."));
    }

    private void freeze(CommandSender sender, String[] args) {
        if (args.length < 2 && !args[0].equalsIgnoreCase("freeze")) { sender.sendMessage(fmt.error("Usage: /staffinspect <freeze|unfreeze> <player>")); return; }
        if (!sender.hasPermission("vs.staffinspect.freeze") && !sender.hasPermission("vs.admin")) { sender.sendMessage(fmt.permissionDenied()); return; }
        Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : nearestPlayer((Player) sender);
        if (target == null) { sender.sendMessage(fmt.error("Player must be online.")); return; }
        boolean enable = args[0].equalsIgnoreCase("freeze");
        if ((args.length < 3 || !args[2].equalsIgnoreCase("confirmed")) && sender instanceof Player staff && dialogs() != null) {
            dialogs().openConfirmation(staff, enable ? "Confirm Freeze" : "Confirm Unfreeze", "Confirm action for " + target.getName() + ".", "staffinspect " + args[0] + " " + target.getName() + " confirmed", "players");
            return;
        }
        if (enable) frozen.add(target.getUniqueId()); else frozen.remove(target.getUniqueId());
        plugin.getAuditLogger().log(sender instanceof Player p ? p.getUniqueId() : null, sender.getName(), enable ? "STAFF_FREEZE" : "STAFF_UNFREEZE", "PLAYER", target.getUniqueId().toString(), "target=" + target.getName());
        target.sendMessage(fmt.warn(enable ? "You have been frozen by staff." : "You have been unfrozen."));
        sender.sendMessage(fmt.success(target.getName() + (enable ? " frozen." : " unfrozen.")));
    }

    @EventHandler(ignoreCancelled = true) public void onMove(PlayerMoveEvent event) {
        if (!frozen.contains(event.getPlayer().getUniqueId()) || event.getTo() == null) return;
        if (event.getFrom().getBlockX() != event.getTo().getBlockX() || event.getFrom().getBlockY() != event.getTo().getBlockY() || event.getFrom().getBlockZ() != event.getTo().getBlockZ()) event.setTo(event.getFrom());
    }

    private boolean isWanted(UUID player) { try (Connection c = plugin.getDatabase().getConnection(); PreparedStatement ps = c.prepareStatement("SELECT 1 FROM wanted_players WHERE player_uuid=? LIMIT 1")) { ps.setString(1, player.toString()); ResultSet rs = ps.executeQuery(); return rs.next(); } catch (Exception ignored) { return false; } }
    private boolean matchesDistrict(UUID player, String query) { DistrictService s = service(DistrictService.class); DistrictData.District d = s == null ? null : s.getPlayerDistrict(player); return d != null && d.getName().toLowerCase(Locale.ROOT).contains(query); }
    private boolean matchesRank(UUID player, String query) { AccessService s = service(AccessService.class); return s != null && s.getPrimaryGroup(player).toLowerCase(Locale.ROOT).contains(query); }
    private void printPage(CommandSender sender, List<OfflinePlayer> all, int page, String title) { int pages=Math.max(1,(all.size()+PAGE_SIZE-1)/PAGE_SIZE); page=Math.max(1,Math.min(page,pages)); if(sender instanceof Player staff && dialogs()!=null){List<DialogMenuItem> items=new ArrayList<>();for(OfflinePlayer p:all.subList((page-1)*PAGE_SIZE,Math.min(all.size(),page*PAGE_SIZE)))items.add(DialogMenuItem.adminItem(safeName(p),p.isOnline()?"Online":"Offline","staffinspect "+safeName(p),"vs.staffinspect",Material.PLAYER_HEAD));if(page>1)items.add(DialogMenuItem.item("Previous","Previous page.","staffinspect "+title.toLowerCase().split(":" )[0]+" "+(page-1),null,Material.ARROW));dialogs().openResult(staff,title+" - "+page+"/"+pages,"Select a player to inspect.",items);return;} sender.sendMessage(fmt.header(title + " (" + all.size() + ") - Page " + page + "/" + pages)); for (OfflinePlayer p : all.subList((page-1)*PAGE_SIZE, Math.min(all.size(), page*PAGE_SIZE))) sender.sendMessage(fmt.info("&e" + safeName(p) + " &7| " + (p.isOnline()?"&aOnline":"&7Offline") + " &8| &f/staffinspect " + safeName(p))); }
    private int parsePage(String value) { try { return Integer.parseInt(value); } catch (NumberFormatException e) { return 1; } }
    private String safeName(OfflinePlayer player) { return player.getName() == null ? player.getUniqueId().toString() : player.getName(); }
    private void usage(CommandSender sender) { sender.sendMessage(fmt.info("/staffinspect <player>|search <query>|online [page]|recent [page]|wanted [page]|frozen [page]")); }
    private Player nearestPlayer(Player staff) { return Bukkit.getOnlinePlayers().stream().filter(p -> !p.equals(staff) && p.getWorld().equals(staff.getWorld())).min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(staff.getLocation()))).orElse(null); }
    private <T> T service(Class<T> type) { try { return plugin.getServiceRegistry().get(type); } catch (RuntimeException ignored) { return null; } }
    private DialogService dialogs() { return service(DialogService.class); }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) { if(args.length==1) return List.of("search","online","recent","wanted","frozen","freeze","unfreeze","tp","bring","spectate","inventory","ender","cash","vaults","roles"); if(args.length==2) return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n->n.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList(); return List.of(); }
}

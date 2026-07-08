package com.vaultsurvival.plugin.npc;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * NPC management commands.
 *
 * /npc create <name> <skinUsername> [action] [data]        — Create NPC at your location
 * /npc remove <id>                                          — Delete an NPC
 * /npc movehere <id>                                        — Move NPC to your location
 * /npc skin <id> <skinUsername>                             — Change NPC skin
 * /npc command <id> <command>                               — Set NPC to run a command
 * /npc shop <id>                                            — Set NPC to open a shop
 * /npc market <id>                                          — Set NPC to open Auction Hall
 * /npc additem <id> <price> [command]                       — Add held item to NPC shop
 * /npc clearitems <id>                                      — Clear NPC shop items
 * /npc list                                                 — List all NPCs
 * /npc tphere <id>                                          — Teleport an NPC to you (owner only)
 */
public class NpcCommand implements CommandExecutor, TabCompleter {

    private final VaultSurvivalPlugin plugin;
    private final NpcService npcService;
    private final MessageFormatter fmt;

    public NpcCommand(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.npcService = plugin.getServiceRegistry().get(NpcService.class);
        this.fmt = plugin.getMessageFormatter();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("vs.npc.admin")) {
            sender.sendMessage(fmt.permissionDenied());
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "create" -> handleCreate(sender, args);
            case "remove" -> handleRemove(sender, args);
            case "movehere" -> handleMoveHere(sender, args);
            case "skin" -> handleSkin(sender, args);
            case "command" -> handleSetCommand(sender, args);
            case "shop" -> handleSetShop(sender, args);
            case "market" -> handleSetMarket(sender, args);
            case "additem" -> handleAddItem(sender, args);
            case "clearitems" -> handleClearItems(sender, args);
            case "list" -> handleList(sender);
            case "tphere" -> handleTpHere(sender, args);
            default -> { sendUsage(sender); yield true; }
        };
    }

    private boolean handleCreate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(fmt.error("Only players can create NPCs at their location."));
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage(fmt.error("Usage: /npc create <name> <skinUsername> [COMMAND|SHOP|MARKET|NONE] [data]"));
            return true;
        }

        String name = args[1];
        String skinUsername = args[2];
        NpcData.ActionType actionType = NpcData.ActionType.NONE;
        String actionData = "";

        if (args.length >= 4) {
            try {
                actionType = NpcData.ActionType.valueOf(args[3].toUpperCase());
            } catch (IllegalArgumentException e) {
                sender.sendMessage(fmt.error("Unknown action type. Use: COMMAND, SHOP, MARKET, NONE"));
                return true;
            }
            if (args.length >= 5) {
                actionData = String.join(" ", Arrays.copyOfRange(args, 4, args.length));
            }
        }

        NpcData.Npc npc = npcService.createNpc(name, skinUsername, player.getLocation(),
            actionType, actionData);

        if (npc != null) {
            sender.sendMessage(fmt.success("Created NPC #" + npc.getId() + ": &e" + name +
                " &7(skin: &e" + skinUsername + "&7)"));
            sender.sendMessage(fmt.info("Action: &e" + actionType + " &8| &7Use &e/npc additem " +
                npc.getId() + " <price> &7for shop items"));
        } else {
            sender.sendMessage(fmt.error("Failed to create NPC."));
        }
        return true;
    }

    private boolean handleRemove(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(fmt.error("Usage: /npc remove <id>"));
            return true;
        }
        int id = parseInt(args[1]);
        if (id < 0) { sender.sendMessage(fmt.error("Invalid ID.")); return true; }

        if (npcService.removeNpc(id)) {
            sender.sendMessage(fmt.success("Removed NPC #" + id));
        } else {
            sender.sendMessage(fmt.error("NPC #" + id + " not found."));
        }
        return true;
    }

    private boolean handleMoveHere(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(fmt.error("Only players can move NPCs."));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(fmt.error("Usage: /npc movehere <id>"));
            return true;
        }
        int id = parseInt(args[1]);
        if (id < 0) { sender.sendMessage(fmt.error("Invalid ID.")); return true; }

        if (npcService.moveNpc(id, player.getLocation())) {
            sender.sendMessage(fmt.success("Moved NPC #" + id + " to your location."));
        } else {
            sender.sendMessage(fmt.error("NPC #" + id + " not found."));
        }
        return true;
    }

    private boolean handleSkin(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(fmt.error("Usage: /npc skin <id> <skinUsername>"));
            return true;
        }
        int id = parseInt(args[1]);
        if (id < 0) { sender.sendMessage(fmt.error("Invalid ID.")); return true; }

        if (npcService.setNpcSkin(id, args[2])) {
            sender.sendMessage(fmt.success("Changed NPC #" + id + " skin to &e" + args[2]));
        } else {
            sender.sendMessage(fmt.error("NPC #" + id + " not found."));
        }
        return true;
    }

    private boolean handleSetCommand(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(fmt.error("Usage: /npc command <id> <command>"));
            sender.sendMessage(fmt.info("Use %player% for the clicking player's name."));
            return true;
        }
        int id = parseInt(args[1]);
        if (id < 0) { sender.sendMessage(fmt.error("Invalid ID.")); return true; }

        String cmd = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        if (npcService.setNpcAction(id, NpcData.ActionType.COMMAND, cmd)) {
            sender.sendMessage(fmt.success("NPC #" + id + " will now run: &e/" + cmd));
        } else {
            sender.sendMessage(fmt.error("NPC #" + id + " not found."));
        }
        return true;
    }

    private boolean handleSetShop(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(fmt.error("Usage: /npc shop <id>"));
            return true;
        }
        int id = parseInt(args[1]);
        if (id < 0) { sender.sendMessage(fmt.error("Invalid ID.")); return true; }

        if (npcService.setNpcAction(id, NpcData.ActionType.SHOP, "")) {
            sender.sendMessage(fmt.success("NPC #" + id + " is now a shop."));
            sender.sendMessage(fmt.info("Add items with &e/npc additem " + id + " <price>"));
        } else {
            sender.sendMessage(fmt.error("NPC #" + id + " not found."));
        }
        return true;
    }

    private boolean handleSetMarket(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(fmt.error("Usage: /npc market <id>"));
            return true;
        }
        int id = parseInt(args[1]);
        if (id < 0) { sender.sendMessage(fmt.error("Invalid ID.")); return true; }

        if (npcService.setNpcAction(id, NpcData.ActionType.MARKET, "")) {
            sender.sendMessage(fmt.success("NPC #" + id + " now opens the Auction Hall."));
        } else {
            sender.sendMessage(fmt.error("NPC #" + id + " not found."));
        }
        return true;
    }

    private boolean handleAddItem(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(fmt.error("Only players can add shop items."));
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(fmt.error("Usage: /npc additem <npc_id> <price> [command_on_purchase]"));
            sender.sendMessage(fmt.info("Hold the item you want to sell in your main hand."));
            return true;
        }

        int id = parseInt(args[1]);
        if (id < 0) { sender.sendMessage(fmt.error("Invalid ID.")); return true; }

        long price;
        try {
            price = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(fmt.error("Invalid price."));
            return true;
        }

        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType() == Material.AIR) {
            sender.sendMessage(fmt.error("Hold an item in your main hand to add it to the shop."));
            return true;
        }

        String cmdOnPurchase = args.length >= 4 ?
            String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : null;

        // Calculate next available slot by counting existing DB items
        int slot = 10; // start at slot 10 (row 2, col 1)
        int itemCount = countShopItems(id);
        slot += itemCount;
        if (slot > 34) slot = 10; // wrap if full (max 25 items in rows 2-4)

        if (npcService.addShopItem(id, slot, held, price, cmdOnPurchase)) {
            sender.sendMessage(fmt.success("Added shop item for &6" + fmt.formatMoney(price,
                plugin.getConfigManager().getCurrencyName(),
                plugin.getConfigManager().getCurrencyNamePlural())));
            sender.sendMessage(fmt.info("Right-click NPC #" + id + " to see the shop."));
        } else {
            sender.sendMessage(fmt.error("Failed to add shop item."));
        }
        return true;
    }

    private boolean handleClearItems(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(fmt.error("Usage: /npc clearitems <id>"));
            return true;
        }
        int id = parseInt(args[1]);
        if (id < 0) { sender.sendMessage(fmt.error("Invalid ID.")); return true; }

        if (npcService.clearShopItems(id)) {
            sender.sendMessage(fmt.success("Cleared all shop items from NPC #" + id));
        }
        return true;
    }

    private boolean handleList(CommandSender sender) {
        var npcs = npcService.getAllNpcs();
        sender.sendMessage(fmt.header("NPCs (" + npcs.size() + ")"));
        if (npcs.isEmpty()) {
            sender.sendMessage(fmt.info("No NPCs configured. Create one with &e/npc create <name> <skin>"));
        } else {
            for (var npc : npcs) {
                sender.sendMessage(fmt.info(
                    "&e#" + npc.getId() + " &f" + npc.getName() +
                    " &7(" + npc.getSkinUsername() + ") &8| " +
                    npc.getActionType() + " &8| " +
                    npc.getWorldName() + " " + (int)npc.getX() + "," + (int)npc.getY() + "," + (int)npc.getZ()
                ));
            }
        }
        return true;
    }

    private boolean handleTpHere(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(fmt.error("Only players can teleport NPCs."));
            return true;
        }
        if (!player.hasPermission("vs.npc.admin.tp")) {
            sender.sendMessage(fmt.permissionDenied());
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(fmt.error("Usage: /npc tphere <id>"));
            return true;
        }
        int id = parseInt(args[1]);
        if (id < 0) { sender.sendMessage(fmt.error("Invalid ID.")); return true; }

        if (npcService.moveNpc(id, player.getLocation())) {
            sender.sendMessage(fmt.success("Teleported NPC #" + id + " to you."));
        } else {
            sender.sendMessage(fmt.error("NPC #" + id + " not found."));
        }
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(fmt.header("NPC Commands"));
        sender.sendMessage(fmt.info("/npc create <name> <skinUsername> [action] [data] &8- Create NPC"));
        sender.sendMessage(fmt.info("/npc remove <id> &8- Delete NPC"));
        sender.sendMessage(fmt.info("/npc movehere <id> &8- Move NPC to you"));
        sender.sendMessage(fmt.info("/npc skin <id> <skinUsername> &8- Change skin"));
        sender.sendMessage(fmt.info("/npc command <id> <command> &8- Set command action"));
        sender.sendMessage(fmt.info("/npc shop <id> &8- Set shop action"));
        sender.sendMessage(fmt.info("/npc market <id> &8- Set Auction Hall action"));
        sender.sendMessage(fmt.info("/npc additem <id> <price> [cmd] &8- Add held item to shop"));
        sender.sendMessage(fmt.info("/npc clearitems <id> &8- Clear shop items"));
        sender.sendMessage(fmt.info("/npc list &8- List all NPCs"));
        sender.sendMessage(fmt.info("/npc tphere <id> &8- Teleport NPC to you"));
        sender.sendMessage(fmt.info("&7Actions: COMMAND, SHOP, MARKET, NONE"));
        sender.sendMessage(fmt.info("&7Use %player% in commands for the clicking player."));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("create", "remove", "movehere", "skin", "command",
                "shop", "market", "additem", "clearitems", "list", "tphere")
                .stream().filter(a -> a.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("create")) {
            return npcService.getAllNpcs().stream()
                .map(n -> String.valueOf(n.getId()))
                .filter(id -> id.startsWith(args[1]))
                .collect(Collectors.toList());
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("create")) {
            return Arrays.asList("COMMAND", "SHOP", "MARKET", "NONE")
                .stream().filter(a -> a.toLowerCase().startsWith(args[3].toLowerCase())).toList();
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("skin") || args[0].equalsIgnoreCase("create"))) {
            return List.of("Notch", "Dream", "Technoblade", "TommyInnit", "Philza",
                "GeorgeNotFound", "Sapnap", "BadBoyHalo");
        }
        return List.of();
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return -1; }
    }

    private int countShopItems(int npcId) {
        try (var conn = plugin.getDatabase().getConnection();
             var ps = conn.prepareStatement("SELECT COUNT(*) FROM npc_shop_items WHERE npc_id = ?")) {
            ps.setInt(1, npcId);
            var rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                "Failed to count shop items for NPC " + npcId, e);
        }
        return 0;
    }
}

package com.vaultsurvival.plugin.social;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.*;

public class ContractCommand implements CommandExecutor, TabCompleter {
    private final ContractService service;
    private final MessageFormatter fmt;

    public ContractCommand(VaultSurvivalPlugin plugin) {
        this.service = plugin.getServiceRegistry().get(ContractService.class);
        this.fmt = plugin.getMessageFormatter();
    }

    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length == 0) { sendUsage(player); return true; }
        return switch (args[0].toLowerCase()) {
            case "create" -> handleCreate(player, args);
            case "accept" -> handleAccept(player, args);
            case "complete" -> handleComplete(player, args);
            case "cancel" -> handleCancel(player, args);
            case "list" -> handleList(player);
            default -> { sendUsage(player); yield true; }
        };
    }

    private boolean handleCreate(Player player, String[] args) {
        if (args.length < 5) { player.sendMessage(fmt.error("Usage: /contract create <player> <amount> <hours> <description>")); return true; }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) { player.sendMessage(fmt.playerNotFound(args[1])); return true; }
        long amount = Long.parseLong(args[2]);
        long hours = Long.parseLong(args[3]);
        String desc = String.join(" ", Arrays.copyOfRange(args, 4, args.length));
        var c = service.createContract(player.getUniqueId(), target.getUniqueId(), desc, amount, hours);
        if (c != null) {
            player.sendMessage(fmt.success("Contract #" + c.getId() + " sent to &e" + target.getName()));
            target.sendMessage(fmt.info("&e" + player.getName() + " &7sent you a contract: " + desc + " &8(#" + c.getId() + ")"));
        }
        return true;
    }
    private boolean handleAccept(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(fmt.error("Usage: /contract accept <id>")); return true; }
        int id = Integer.parseInt(args[1]);
        if (service.acceptContract(id, player.getUniqueId())) player.sendMessage(fmt.success("Contract accepted!"));
        return true;
    }
    private boolean handleComplete(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(fmt.error("Usage: /contract complete <id>")); return true; }
        int id = Integer.parseInt(args[1]);
        if (service.completeContract(id, player.getUniqueId())) player.sendMessage(fmt.success("Contract completed! Payment transferred."));
        return true;
    }
    private boolean handleCancel(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage(fmt.error("Usage: /contract cancel <id>")); return true; }
        int id = Integer.parseInt(args[1]);
        if (service.cancelContract(id, player.getUniqueId())) player.sendMessage(fmt.success("Contract cancelled."));
        return true;
    }
    private boolean handleList(Player player) {
        var list = service.getContracts(player.getUniqueId());
        player.sendMessage(fmt.header("Contracts (" + list.size() + ")"));
        for (var c : list) {
            @SuppressWarnings("deprecation") String issuer = Bukkit.getOfflinePlayer(c.getIssuerUuid()).getName();
            @SuppressWarnings("deprecation") String target = Bukkit.getOfflinePlayer(c.getTargetUuid()).getName();
            player.sendMessage(fmt.info("&e#" + c.getId() + " &7" + c.getStatus() + " &8| " + issuer + " -> " + target + " &8| &6" + c.getAmount()));
        }
        return true;
    }
    private void sendUsage(Player p) {
        p.sendMessage(fmt.header("Contract Commands"));
        p.sendMessage(fmt.info("/contract create <player> <amount> <hours> <desc> &8- Create contract"));
        p.sendMessage(fmt.info("/contract accept <id> &8- Accept a contract"));
        p.sendMessage(fmt.info("/contract complete <id> &8- Complete a contract"));
        p.sendMessage(fmt.info("/contract cancel <id> &8- Cancel a contract"));
        p.sendMessage(fmt.info("/contract list &8- List your contracts"));
    }
    @Override public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        if (args.length == 1) return Arrays.asList("create","accept","complete","cancel","list");
        return List.of();
    }
}

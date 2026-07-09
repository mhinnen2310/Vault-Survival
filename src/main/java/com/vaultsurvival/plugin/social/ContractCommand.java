package com.vaultsurvival.plugin.social;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class ContractCommand implements CommandExecutor, TabCompleter {
    private final VaultSurvivalPlugin plugin;
    private final ContractService contracts;
    private final EscrowService escrow;
    private final PayoutLockerService payouts;
    private final ContractAuditService audit;
    private final ContractDisputeService disputes;
    private final MessageFormatter fmt;

    public ContractCommand(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.contracts = plugin.getServiceRegistry().get(ContractService.class);
        this.escrow = plugin.getServiceRegistry().get(EscrowService.class);
        this.payouts = plugin.getServiceRegistry().get(PayoutLockerService.class);
        this.audit = plugin.getServiceRegistry().get(ContractAuditService.class);
        this.disputes = plugin.getServiceRegistry().get(ContractDisputeService.class);
        this.fmt = plugin.getMessageFormatter();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        return switch (cmd.getName().toLowerCase()) {
            case "escrow" -> handleEscrow(sender, args);
            case "payouts" -> handlePayouts(sender, args);
            default -> handleContract(sender, args);
        };
    }

    private boolean handleContract(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }
        return switch (args[0].toLowerCase()) {
            case "create" -> handleCreate(sender, args);
            case "accept" -> handleAccept(sender, args);
            case "complete" -> handleComplete(sender, args);
            case "cancel" -> handleCancel(sender, args);
            case "dispute" -> handleDispute(sender, args);
            case "list" -> handleList(sender);
            case "debug" -> handleDebug(sender);
            case "audit" -> handleAudit(sender);
            default -> {
                sendUsage(sender);
                yield true;
            }
        };
    }

    private boolean handleCreate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(fmt.error("Only players can create contracts."));
            return true;
        }
        if (args.length < 5) {
            player.sendMessage(fmt.error("Usage: /contract create <player> <amount> <hours> <description>"));
            return true;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            player.sendMessage(fmt.playerNotFound(args[1]));
            return true;
        }
        long amount = parseLong(args[2]);
        long hours = parseLong(args[3]);
        if (amount < 0 || hours <= 0) {
            player.sendMessage(fmt.error("Invalid amount or deadline."));
            return true;
        }
        String desc = String.join(" ", Arrays.copyOfRange(args, 4, args.length));
        var c = contracts.createPaidContract(player, target.getUniqueId(), desc, amount, hours,
            ContractData.ContractSource.PLAYER_CONTRACT);
        if (c != null) {
            player.sendMessage(fmt.success("Contract #" + c.getId() + " sent to &e" + target.getName()));
            target.sendMessage(fmt.info("&e" + player.getName() + " &7sent you an escrowed contract: " + desc + " &8(#" + c.getId() + ")"));
        } else {
            player.sendMessage(fmt.error("Contract failed. Paid contracts require escrowed physical cash."));
        }
        return true;
    }

    private boolean handleAccept(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length < 2) {
            player.sendMessage(fmt.error("Usage: /contract accept <id>"));
            return true;
        }
        int id = parseInt(args[1]);
        player.sendMessage(contracts.acceptContract(id, player.getUniqueId())
            ? fmt.success("Contract accepted.")
            : fmt.error("Cannot accept contract. Paid contracts require locked escrow."));
        return true;
    }

    private boolean handleComplete(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length < 2) {
            player.sendMessage(fmt.error("Usage: /contract complete <id>"));
            return true;
        }
        int id = parseInt(args[1]);
        player.sendMessage(contracts.completeContract(id, player.getUniqueId())
            ? fmt.success("Contract completed. Payout moved to payout locker.")
            : fmt.error("Cannot complete contract."));
        return true;
    }

    private boolean handleCancel(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length < 2) {
            player.sendMessage(fmt.error("Usage: /contract cancel <id>"));
            return true;
        }
        int id = parseInt(args[1]);
        player.sendMessage(contracts.cancelContract(id, player.getUniqueId())
            ? fmt.success("Contract cancelled. Remaining escrow moved to payout locker.")
            : fmt.error("Cannot cancel contract."));
        return true;
    }

    private boolean handleDispute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length < 3) {
            player.sendMessage(fmt.error("Usage: /contract dispute <id> <reason>"));
            return true;
        }
        int id = parseInt(args[1]);
        String reason = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        player.sendMessage(contracts.disputeContract(id, player.getUniqueId(), reason)
            ? fmt.success("Dispute opened. Escrow is locked.")
            : fmt.error("Cannot dispute contract."));
        return true;
    }

    private boolean handleList(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(fmt.error("Only players can list personal contracts."));
            return true;
        }
        var list = contracts.getContracts(player.getUniqueId());
        sender.sendMessage(fmt.header("Contracts (" + list.size() + ")"));
        for (var c : list) {
            sender.sendMessage(fmt.info("&e#" + c.getId() + " &7" + c.getStatus()
                + " &8| " + name(c.getIssuerUuid()) + " -> " + name(c.getTargetUuid())
                + " &8| &6" + c.getAmount() + " &8| escrow=&e" + escrow.getLockedAmount(c.getId())));
        }
        return true;
    }

    private boolean handleDebug(CommandSender sender) {
        if (!sender.hasPermission("vs.admin")) {
            sender.sendMessage(fmt.permissionDenied());
            return true;
        }
        sender.sendMessage(fmt.header("Contract Debug"));
        sender.sendMessage(fmt.info("Contracts: &e" + contracts.getAllContracts().size()));
        sender.sendMessage(fmt.info("Escrows: &e" + escrow.getAllEscrows().size()));
        sender.sendMessage(fmt.info("Pending payouts: &e" + payouts.getAllPending().size()));
        sender.sendMessage(fmt.info("Open disputes: &e" + disputes.getOpenDisputeContractIds().size()));
        return true;
    }

    private boolean handleAudit(CommandSender sender) {
        if (!sender.hasPermission("vs.admin")) {
            sender.sendMessage(fmt.permissionDenied());
            return true;
        }
        sender.sendMessage(fmt.header("Contract Audit"));
        for (String row : audit.recent(12)) sender.sendMessage(fmt.info(row));
        return true;
    }

    private boolean handleEscrow(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vs.admin")) {
            sender.sendMessage(fmt.permissionDenied());
            return true;
        }
        sender.sendMessage(fmt.header("Escrow Debug"));
        for (var e : escrow.getAllEscrows().stream().limit(20).toList()) {
            sender.sendMessage(fmt.info("&e#" + e.getId() + " &7contract=" + e.getContractId()
                + " &8| &6" + e.getAmount() + " &8| &7" + e.getStatus() + " &8| " + e.getSourceType()));
        }
        return true;
    }

    private boolean handlePayouts(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(fmt.error("Only players can use payout lockers."));
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("claim")) {
            payouts.claim(player);
            return true;
        }
        var pending = payouts.getPending(player.getUniqueId());
        player.sendMessage(fmt.header("Payout Locker"));
        player.sendMessage(fmt.info("Pending total: &6" + payouts.getPendingTotal(player.getUniqueId())));
        for (var entry : pending) {
            player.sendMessage(fmt.info("&e#" + entry.getId() + " &7" + entry.getSourceType()
                + " &8| &6" + entry.getAmount() + " &8| &7" + entry.getDetails()));
        }
        player.sendMessage(fmt.info("Use &e/payouts claim &7to receive physical cash."));
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(fmt.header("Contract Commands"));
        sender.sendMessage(fmt.info("/contract create <player> <amount> <hours> <desc>"));
        sender.sendMessage(fmt.info("/contract accept <id>"));
        sender.sendMessage(fmt.info("/contract complete <id>"));
        sender.sendMessage(fmt.info("/contract cancel <id>"));
        sender.sendMessage(fmt.info("/contract dispute <id> <reason>"));
        sender.sendMessage(fmt.info("/contract list"));
        sender.sendMessage(fmt.info("/contract debug"));
        sender.sendMessage(fmt.info("/contract audit"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("payouts")) {
            return args.length == 1 ? List.of("claim").stream().filter(v -> v.startsWith(args[0].toLowerCase())).toList() : List.of();
        }
        if (command.getName().equalsIgnoreCase("escrow")) {
            return args.length == 1 ? List.of("debug").stream().filter(v -> v.startsWith(args[0].toLowerCase())).toList() : List.of();
        }
        if (args.length == 1) {
            return Arrays.asList("create", "accept", "complete", "cancel", "dispute", "list", "debug", "audit")
                .stream().filter(v -> v.startsWith(args[0].toLowerCase())).toList();
        }
        return List.of();
    }

    private String name(UUID uuid) {
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name != null ? name : uuid.toString().substring(0, 8);
    }

    private static int parseInt(String raw) {
        try { return Integer.parseInt(raw); } catch (Exception ignored) { return -1; }
    }

    private static long parseLong(String raw) {
        try { return Long.parseLong(raw); } catch (Exception ignored) { return -1; }
    }
}

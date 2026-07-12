package com.vaultsurvival.plugin.commands;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;
import java.util.*;

public class ResourcePackCommand implements CommandExecutor, TabCompleter {
    private final VaultSurvivalPlugin plugin;
    private final MessageFormatter fmt;

    public ResourcePackCommand(VaultSurvivalPlugin plugin) {
        this.plugin = plugin; this.fmt = plugin.getMessageFormatter();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("vs.admin")) {
            sender.sendMessage(fmt.permissionDenied());
            return true;
        }

        String url = plugin.getConfigManager().getResourcePackUrl();
        String hash = plugin.getConfigManager().getResourcePackHash();
        boolean required = plugin.getConfigManager().isResourcePackRequired();

        if (url.isEmpty()) {
            sender.sendMessage(fmt.error("No resource pack URL configured. Set resourcepack.url in config.yml"));
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            plugin.getConfigManager().reload();
            sender.sendMessage(fmt.success("Config reloaded. New resource pack settings applied."));
            url = plugin.getConfigManager().getResourcePackUrl();
            hash = plugin.getConfigManager().getResourcePackHash();
            required = plugin.getConfigManager().isResourcePackRequired();
        }

        Player target = null;
        if (args.length > 0 && !args[0].equalsIgnoreCase("reload")) {
            target = plugin.getServer().getPlayer(args[0]);
        } else if (sender instanceof Player player) {
            target = player;
        }

        if (target != null) {
            byte[] hashBytes = null;
            if (!hash.isEmpty()) {
                try {
                    hashBytes = hexToBytes(hash);
                } catch (Exception ignored) {}
            }
            target.setResourcePack(url, hashBytes, Component.text("Server Resource Pack"), required);
            sender.sendMessage(fmt.success("Resource pack sent to &e" + target.getName()));
        } else if (!(sender instanceof Player)) {
            // Send to all online players
            byte[] hashBytes = null;
            if (!hash.isEmpty()) {
                try { hashBytes = hexToBytes(hash); } catch (Exception ignored) {}
            }
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                p.setResourcePack(url, hashBytes, Component.text("Server Resource Pack"), required);
            }
            sender.sendMessage(fmt.success("Resource pack sent to all online players"));
        }

        return true;
    }

    private byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i+1), 16));
        }
        return data;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1 || !sender.hasPermission("vs.admin")) return List.of();
        String typed = args[0].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>(); options.add("reload");
        plugin.getServer().getOnlinePlayers().forEach(player -> options.add(player.getName()));
        return options.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(typed)).sorted().toList();
    }
}

package com.vaultsurvival.plugin.access;

import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.Arrays;
import java.util.Locale;

/**
 * Routes the local server-console rank command before Paper's Brigadier tree.
 * Some server stacks reserve the plain {@code rank} label, even when the
 * plugin command is registered, which otherwise makes console administration
 * look like an incomplete command.
 */
final class ConsoleRankCommandListener implements Listener {

    private final AccessCommand accessCommand;

    ConsoleRankCommandListener(AccessCommand accessCommand) {
        this.accessCommand = accessCommand;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        String raw = event.getCommand().trim();
        if (raw.startsWith("/")) {
            raw = raw.substring(1).trim();
        }
        if (raw.isEmpty()) {
            return;
        }

        String[] parts = raw.split("\\s+");
        if (!parts[0].toLowerCase(Locale.ROOT).equals("rank")) {
            return;
        }

        event.setCancelled(true);
        CommandSender sender = event.getSender();
        accessCommand.execute(sender, Arrays.copyOfRange(parts, 1, parts.length));
    }
}

package com.vaultsurvival.plugin.chat;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.ConfigManager;
import com.vaultsurvival.plugin.core.MessageFormatter;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Handles chat formatting with rank and district labels.
 */
public class ChatListener implements Listener {

    private final ConfigManager config;
    private final MessageFormatter fmt;
    private final ChatFormatService chatFormatService;

    public ChatListener(VaultSurvivalPlugin plugin) {
        this.config = plugin.getConfigManager();
        this.fmt = plugin.getMessageFormatter();
        this.chatFormatService = new ChatFormatService(plugin);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerChat(AsyncChatEvent event) {
        if (!config.isChatEnabled()) {
            return;
        }

        String message = LegacyComponentSerializer.legacySection()
            .serialize(event.message());
        String formatted = chatFormatService.formatChat(event.getPlayer(), message);

        event.renderer((source, sourceDisplayName, msg, viewer) ->
            fmt.deserialize(formatted)
        );
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        String rankLabel = chatFormatService.getRankLabel(player);

        if (config.isChatTabPrefix() && !rankLabel.isEmpty()) {
            player.playerListName(fmt.deserialize(rankLabel + " &r" + player.getName()));
        }

        if (config.isChatNametagPrefix() && !rankLabel.isEmpty()) {
            player.displayName(fmt.deserialize(rankLabel + " &r" + player.getName()));
        }
    }
}

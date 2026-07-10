package com.vaultsurvival.plugin.security;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.access.AccessService;
import com.vaultsurvival.plugin.core.MessageFormatter;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Set;
import java.util.UUID;

/** Fail-closed access gate and visible warning for the dedicated staff sandbox. */
public final class StaffSandboxGuard implements Listener {
    private final VaultSurvivalPlugin plugin;
    private final Set<UUID> allowedUuids;
    private final MessageFormatter fmt;

    public StaffSandboxGuard(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.allowedUuids = plugin.getConfigManager().getStaffSandboxAllowedUuids();
        this.fmt = plugin.getMessageFormatter();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (allowedUuids.contains(event.getUniqueId())) return;
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST,
            Component.text("This isolated test environment is restricted to explicitly approved staff."));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        event.joinMessage(null);
        AccessService access = plugin.getServiceRegistry().get(AccessService.class);
        access.addToGroup(event.getPlayer().getUniqueId(), "owner", event.getPlayer().getUniqueId());
        access.refreshPlayerPermissions(event.getPlayer());
        event.getPlayer().sendMessage(fmt.header("STAFF SANDBOX"));
        event.getPlayer().sendMessage(fmt.warn("This is an isolated, unlimited test environment. Nothing here is production data."));
        event.getPlayer().sendMessage(fmt.info("World, playerdata, statistics, advancements, inventories, economy, districts, vaults, markets, and audit logs are sandbox-only."));
        event.getPlayer().sendMessage(fmt.info("Use &e/staffmode return&7 to return to the production server."));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        event.quitMessage(null);
    }
}

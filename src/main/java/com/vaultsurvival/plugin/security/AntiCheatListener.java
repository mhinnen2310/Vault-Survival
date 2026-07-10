package com.vaultsurvival.plugin.security;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Conservative movement, combat, break, and inventory scoring. Signals never auto-ban. */
public final class AntiCheatListener implements Listener {
    private final VaultSurvivalPlugin plugin;
    private final Map<UUID, Long> breaks = new HashMap<>();
    private final Map<UUID, Long> attacks = new HashMap<>();
    private final Map<UUID, Long> clicks = new HashMap<>();
    private final Map<UUID, Integer> rapidClicks = new HashMap<>();
    private final Map<String, Long> alertCooldowns = new HashMap<>();

    public AntiCheatListener(VaultSurvivalPlugin plugin) { this.plugin = plugin; }

    @EventHandler(ignoreCancelled = true)
    public void move(PlayerMoveEvent event) {
        if (!enabled()) return;
        Player player = event.getPlayer();
        if (event.getTo() == null || player.getAllowFlight() || player.isGliding() || player.isInsideVehicle() || exempt(player)) return;
        if (!event.getFrom().getWorld().equals(event.getTo().getWorld())) return;
        double horizontal = Math.pow(event.getTo().getX() - event.getFrom().getX(), 2) + Math.pow(event.getTo().getZ() - event.getFrom().getZ(), 2);
        double maximum = plugin.getConfigManager().getConfig().getDouble("security.anticheat.movementMaxDistanceSquared", 36);
        if (horizontal > maximum) flag(player, "MOVEMENT_SPEED", 4, "horizontalSquared=" + String.format(java.util.Locale.ROOT, "%.2f", horizontal));
        if (!player.isOnGround() && event.getTo().getY() - event.getFrom().getY() > .9) flag(player, "MOVEMENT_FLY", 3, "vertical ascent");
    }

    @EventHandler(ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent event) {
        if (!enabled() || exempt(event.getPlayer())) return;
        long now = System.currentTimeMillis(); Long previous = breaks.put(event.getPlayer().getUniqueId(), now);
        if (previous != null && now - previous < plugin.getConfigManager().getConfig().getLong("security.anticheat.fastBreakMinIntervalMs", 45)) {
            flag(event.getPlayer(), "FAST_BREAK", 1, "interval=" + (now - previous) + " block=" + event.getBlock().getType());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void combat(EntityDamageByEntityEvent event) {
        if (!enabled() || !(event.getDamager() instanceof Player attacker) || exempt(attacker)) return;
        double reachSquared = attacker.getEyeLocation().distanceSquared(event.getEntity().getLocation().add(0, Math.min(1.0, event.getEntity().getHeight() / 2), 0));
        double maximumReach = plugin.getConfigManager().getConfig().getDouble("security.anticheat.combatMaxReachSquared", 22.0);
        if (reachSquared > maximumReach) flag(attacker, "COMBAT_REACH", 4, "reachSquared=" + String.format(java.util.Locale.ROOT, "%.2f", reachSquared));
        long now = System.currentTimeMillis(); Long previous = attacks.put(attacker.getUniqueId(), now);
        long minimum = plugin.getConfigManager().getConfig().getLong("security.anticheat.combatMinAttackIntervalMs", 35);
        if (previous != null && now - previous < minimum) flag(attacker, "COMBAT_SPEED", 2, "interval=" + (now - previous));
    }

    @EventHandler(ignoreCancelled = true)
    public void inventoryClick(InventoryClickEvent event) {
        if (!enabled() || !(event.getWhoClicked() instanceof Player player) || exempt(player)) return;
        detectOversized(player, event.getCurrentItem(), "clicked");
        detectOversized(player, event.getCursor(), "cursor");
        long now = System.currentTimeMillis(); Long previous = clicks.put(player.getUniqueId(), now);
        if (previous != null && now - previous < 15) {
            int count = rapidClicks.merge(player.getUniqueId(), 1, Integer::sum);
            if (count >= 12) { flag(player, "INVENTORY_CLICK_RATE", 2, "12+ clicks below 15ms"); rapidClicks.put(player.getUniqueId(), 0); }
        } else rapidClicks.put(player.getUniqueId(), 0);
    }

    @EventHandler(ignoreCancelled = true)
    public void creativeInventory(InventoryCreativeEvent event) {
        if (!enabled() || !(event.getWhoClicked() instanceof Player player) || exempt(player)) return;
        if (player.getGameMode() == GameMode.CREATIVE && !player.hasPermission("vs.admin")) {
            flag(player, "INVENTORY_CREATIVE_INJECTION", 5, "creative slot=" + event.getSlot());
        }
        detectOversized(player, event.getCursor(), "creative cursor");
    }

    @SuppressWarnings("deprecation")
    @EventHandler(ignoreCancelled = true)
    public void pickup(PlayerPickupItemEvent event) {
        if (!enabled() || exempt(event.getPlayer())) return;
        detectOversized(event.getPlayer(), event.getItem().getItemStack(), "pickup");
    }

    private void detectOversized(Player player, ItemStack item, String source) {
        if (item != null && !item.getType().isAir() && item.getAmount() > item.getMaxStackSize()) {
            flag(player, "INVENTORY_OVERSIZED_STACK", 5, source + " " + item.getType() + " amount=" + item.getAmount() + " max=" + item.getMaxStackSize());
        }
    }

    private void flag(Player player, String check, double score, String details) {
        try {
            plugin.getDatabase().executeUpdate("INSERT INTO anticheat_flags (player_uuid,check_type,score,details,created_at) VALUES (?,?,?,?,?)",
                player.getUniqueId().toString(), check, score, details, System.currentTimeMillis());
            plugin.getAuditLogger().log(player.getUniqueId(), player.getName(), "ANTICHEAT_FLAG", "PLAYER", player.getUniqueId().toString(), check + " score=" + score + " " + details);
            double minimum = plugin.getConfigManager().getConfig().getDouble("security.anticheat.liveAlertMinimumScore", 1);
            String cooldownKey = player.getUniqueId() + ":" + check;
            long now = System.currentTimeMillis(); long last = alertCooldowns.getOrDefault(cooldownKey, 0L);
            if (score >= minimum && now - last >= 10_000L) {
                alertCooldowns.put(cooldownKey, now);
                try {
                    plugin.getServiceRegistry().get(StaffAlertService.class).recordAlert(
                        "ANTICHEAT_" + check, score >= 5 ? "HIGH" : score >= 3 ? "MEDIUM" : "LOW",
                        player.getUniqueId(), player.getName(), check + " score=" + score + " " + details, player.getLocation());
                } catch (RuntimeException ignored) { }
            }
        } catch (Exception ignored) { }
    }

    private boolean enabled() { return plugin.getConfigManager().getConfig().getBoolean("security.anticheat.enabled", true); }
    private boolean exempt(Player player) { return plugin.isStaffModeActive(player.getUniqueId()) || plugin.getConfigManager().isStaffSandbox(); }
}

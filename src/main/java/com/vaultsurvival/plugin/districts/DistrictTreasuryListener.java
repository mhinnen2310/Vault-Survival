package com.vaultsurvival.plugin.districts;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.currency.CurrencyService;
import com.vaultsurvival.plugin.breach.BreachService;
import com.vaultsurvival.plugin.dialogs.DialogMenuItem;
import com.vaultsurvival.plugin.dialogs.DialogService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DistrictTreasuryListener implements Listener {
    private final VaultSurvivalPlugin plugin;
    private final DistrictTreasuryService treasuries;
    private final CurrencyService currency;
    private final Set<UUID> activeBreaches = ConcurrentHashMap.newKeySet();

    public DistrictTreasuryListener(VaultSurvivalPlugin plugin, DistrictTreasuryService treasuries) {
        this.plugin = plugin;
        this.treasuries = treasuries;
        this.currency = plugin.getServiceRegistry().get(CurrencyService.class);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        DistrictTreasuryService.TreasuryVault vault = treasuries.getVault(event.getClickedBlock());
        if (vault == null) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!treasuries.canManage(player, vault)) {
            BreachService breach;
            try { breach = plugin.getServiceRegistry().get(BreachService.class); }
            catch (RuntimeException unavailable) { player.sendMessage(plugin.getMessageFormatter().error("Breach service is unavailable.")); return; }
            ItemStack held = player.getInventory().getItemInMainHand();
            if (!breach.isBreachKit(held)) {
                player.sendMessage(plugin.getMessageFormatter().error("This locked treasury requires MAYOR, CO_MAYOR, or TREASURER. Everyone else must use a breach kit."));
                return;
            }
            if (!activeBreaches.add(player.getUniqueId())) { player.sendMessage(plugin.getMessageFormatter().warn("You already have a treasury breach in progress.")); return; }
            held.setAmount(held.getAmount()-1);
            int seconds=Math.max(1,plugin.getConfigManager().getConfig().getInt("districtTreasury.breach.channelSeconds",5));
            player.sendMessage(plugin.getMessageFormatter().warn("Treasury breach started. Stay within 6 blocks for "+seconds+" seconds; the kit is consumed."));
            plugin.getAuditLogger().log(player.getUniqueId(),player.getName(),"DISTRICT_TREASURY_BREACH_START","DISTRICT_TREASURY",vault.vaultUuid().toString(),"district="+vault.districtId());
            plugin.getServer().getScheduler().runTaskLater(plugin,()->{activeBreaches.remove(player.getUniqueId());var result=treasuries.breach(player,vault.vaultUuid());player.sendMessage(result.success()?plugin.getMessageFormatter().success(result.message()):plugin.getMessageFormatter().error(result.message()));},seconds*20L);
            return;
        }
        if (currency.isCashItem(player.getInventory().getItemInMainHand())) {
            send(player, treasuries.depositHeld(player, vault.vaultUuid()));
            return;
        }
        open(player, vault, "You are physically at this hidden treasury vault.");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBreak(BlockBreakEvent event) {
        if (treasuries.getVault(event.getBlock()) == null) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(plugin.getMessageFormatter().error("Unregister this empty treasury with /district treasury remove before breaking it."));
    }

    public void open(Player player, DistrictTreasuryService.TreasuryVault vault, String status) {
        if (!plugin.getServiceRegistry().has(DialogService.class)) {
            player.sendMessage(plugin.getMessageFormatter().info(status));
            return;
        }
        long balance = treasuries.getVaultBalance(vault.vaultUuid());
        List<DialogMenuItem> actions = new java.util.ArrayList<>();
        for (String amount : List.of("1", "10", "100", "1k", "10k", "1m", "all")) {
            actions.add(DialogMenuItem.item("Deposit " + amount, "Take this exact amount from your physical cash.",
                "district treasury deposit " + vault.vaultUuid() + " " + amount, null, Material.GOLD_NUGGET));
            actions.add(DialogMenuItem.item("Withdraw " + amount, "Create one physical cash item; one empty slot is required.",
                "district treasury withdraw " + vault.vaultUuid() + " " + amount, null, Material.GOLD_INGOT));
        }
        actions.add(DialogMenuItem.item("Refresh", "Refresh this physical vault while standing nearby.",
            "district treasury open " + vault.vaultUuid(), null, Material.CLOCK));
        plugin.getServiceRegistry().get(DialogService.class).openResult(player, "Physical District Treasury",
            status + "\nVault balance: " + balance + "\nHolding cash and right-clicking deposits it immediately.", actions);
    }

    public void send(Player player, DistrictTreasuryService.Result result) {
        if (result.vault() != null && treasuries.isNear(player, result.vault()) && plugin.getServiceRegistry().has(DialogService.class)) {
            open(player, result.vault(), result.message());
        } else player.sendMessage(result.success() ? plugin.getMessageFormatter().success(result.message()) : plugin.getMessageFormatter().error(result.message()));
    }
}

package com.vaultsurvival.plugin.districts;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.currency.CurrencyService;
import com.vaultsurvival.plugin.dialogs.DialogMenuItem;
import com.vaultsurvival.plugin.dialogs.DialogService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.List;

public final class DistrictTreasuryListener implements Listener {
    private final VaultSurvivalPlugin plugin;
    private final DistrictTreasuryService treasuries;
    private final CurrencyService currency;

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
            player.sendMessage(plugin.getMessageFormatter().error("This locked district treasury requires MAYOR, CO_MAYOR, or TREASURER."));
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
        plugin.getServiceRegistry().get(DialogService.class).openResult(player, "Physical District Treasury",
            status + "\nVault balance: " + balance + "\nDeposit by holding physical cash and right-clicking this block.", List.of(
                DialogMenuItem.item("Deposit Held Cash", "Deposit the physical cash in your main hand.", "district treasury deposit " + vault.vaultUuid(), null, Material.GOLD_NUGGET),
                DialogMenuItem.item("Deposit All Cash", "Deposit every valid physical cash item in your inventory.", "district treasury depositall " + vault.vaultUuid(), null, Material.GOLD_BLOCK),
                DialogMenuItem.item("Withdraw 100", "Withdraw here; requires one free inventory slot.", "district treasury withdraw " + vault.vaultUuid() + " 100", null, Material.GOLD_INGOT),
                DialogMenuItem.item("Withdraw 1,000", "Withdraw here; requires one free inventory slot.", "district treasury withdraw " + vault.vaultUuid() + " 1000", null, Material.RAW_GOLD_BLOCK),
                DialogMenuItem.item("Withdraw All", "Withdraw this physical vault's full balance.", "district treasury withdraw " + vault.vaultUuid() + " all", null, Material.CHEST),
                DialogMenuItem.item("Refresh", "Refresh this physical vault while standing nearby.", "district treasury open " + vault.vaultUuid(), null, Material.CLOCK)));
    }

    public void send(Player player, DistrictTreasuryService.Result result) {
        if (result.vault() != null && treasuries.isNear(player, result.vault()) && plugin.getServiceRegistry().has(DialogService.class)) {
            open(player, result.vault(), result.message());
        } else player.sendMessage(result.success() ? plugin.getMessageFormatter().success(result.message()) : plugin.getMessageFormatter().error(result.message()));
    }
}

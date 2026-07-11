package com.vaultsurvival.plugin.merchant;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.Module;
import com.vaultsurvival.plugin.merchant.shop.MerchantShopService;
import com.vaultsurvival.plugin.merchant.shop.MerchantShopServiceImpl;
import com.vaultsurvival.plugin.merchant.shop.MerchantShopEditor;

/**
 * VS-Merchant module: Merchant Buy Orders system.
 *
 * Merchants (players with MERCHANT role) can create buy orders
 * requesting items from players. Orders are funded with physical
 * cash escrow. Players deliver matching items through NPCs or
 * /vsmenu and receive physical payout or payout locker entries.
 *
 * Sprint 10: Merchant-owned NPC shops with player-supplied stock,
 * physical cash payments, district tax, and payout locker earnings.
 */
public class MerchantOrderModule extends Module {

    private MerchantOrderServiceImpl orderService;
    private MerchantShopServiceImpl shopService;

    public MerchantOrderModule(VaultSurvivalPlugin plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "VS-Merchant";
    }

    @Override
    public String[] getDependencies() {
        return new String[] { "VS-Currency", "VS-NPC", "VS-Districts" };
    }

    @Override
    public void onLoad() {
        orderService = new MerchantOrderServiceImpl(plugin);
        plugin.getServiceRegistry().register(MerchantOrderService.class, orderService);

        shopService = new MerchantShopServiceImpl(plugin);
        plugin.getServiceRegistry().register(MerchantShopService.class, shopService);

        plugin.getLogger().info("Merchant Order and Shop services registered");
    }

    @Override
    public void onEnable() {
        // Load orders and shops from database
        orderService.loadAll();
        shopService.loadAll();

        // Register commands
        var cmd = new MerchantOrderCommand(plugin);
        plugin.getCommand("merchant").setExecutor(cmd);
        plugin.getCommand("merchant").setTabCompleter(cmd);
        plugin.getServer().getPluginManager().registerEvents(new MerchantOrderBoardListener(plugin), plugin);
        plugin.getServer().getPluginManager().registerEvents(new MerchantNpcLifecycleListener(plugin), plugin);
        plugin.getServer().getPluginManager().registerEvents(new MerchantShopEditor(plugin, shopService), plugin);

        plugin.getLogger().info("Merchant commands registered");
    }

    @Override
    public void onDisable() {
        plugin.getServiceRegistry().unregister(MerchantOrderService.class);
        plugin.getServiceRegistry().unregister(MerchantShopService.class);
    }

    public MerchantOrderServiceImpl getOrderService() {
        return orderService;
    }

    public MerchantShopServiceImpl getShopService() {
        return shopService;
    }
}

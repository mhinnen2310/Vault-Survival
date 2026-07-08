package com.vaultsurvival.plugin.npc;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.Module;

/**
 * VS-NPC module: Custom NPC system with real player-model entities,
 * Mojang skins, shops, commands, and module integration.
 *
 * NPCs use NMS ServerPlayer for visuals and Bukkit Interaction entities
 * for click detection. Other modules (Market, Currency) hook in via
 * the NpcInteractEvent.
 */
public class NpcModule extends Module {

    private NpcServiceImpl npcService;
    private NpcListener npcListener;

    public NpcModule(VaultSurvivalPlugin plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "VS-NPC";
    }

    @Override
    public String[] getDependencies() {
        return new String[] { "VS-Access" };
    }

    @Override
    public void onLoad() {
        npcService = new NpcServiceImpl(plugin);
        plugin.getServiceRegistry().register(NpcService.class, npcService);
        plugin.getLogger().info("NPC service registered");
    }

    @Override
    public void onEnable() {
        // Register event listener
        npcListener = new NpcListener(plugin);
        plugin.getServer().getPluginManager().registerEvents(npcListener, plugin);

        // Register commands
        var npcCmd = new NpcCommand(plugin);
        plugin.getCommand("npc").setExecutor(npcCmd);
        plugin.getCommand("npc").setTabCompleter(npcCmd);

        // Load and spawn all NPCs from the database
        npcService.loadAndSpawnAll();
    }

    @Override
    public void onDisable() {
        // Remove all interaction entities
        for (var npc : npcService.getAllNpcs()) {
            if (npc.getInteractionUuid() != null) {
                var entity = plugin.getServer().getEntity(npc.getInteractionUuid());
                if (entity != null) entity.remove();
            }
        }
        plugin.getServiceRegistry().unregister(NpcService.class);
    }

    public NpcServiceImpl getNpcService() {
        return npcService;
    }
}

package com.vaultsurvival.plugin.area;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.MessageFormatter;
import com.vaultsurvival.plugin.regions.RegionData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.stream.Collectors;

public class WhereAmICommand implements CommandExecutor {

    private final CurrentAreaService currentAreaService;
    private final MessageFormatter fmt;

    public WhereAmICommand(VaultSurvivalPlugin plugin, CurrentAreaService currentAreaService) {
        this.currentAreaService = currentAreaService;
        this.fmt = plugin.getMessageFormatter();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(fmt.error("Only players can inspect current area context."));
            return true;
        }

        CurrentAreaContext context = currentAreaService.resolve(player);
        player.sendMessage(fmt.header("Current Area"));
        player.sendMessage(fmt.info("Area: &e" + pretty(context.areaType()) + " &8- &7" + context.areaName()));
        player.sendMessage(fmt.info("District: &e" + (context.hasDistrict() ? context.district().getName() : "None")));
        player.sendMessage(fmt.info("Status: &e" + context.playerStatus()));
        player.sendMessage(fmt.info("Regions: &e" + regionSummary(context)));
        player.sendMessage(fmt.info("Flags: &e" + flagSummary(context)));
        player.sendMessage(fmt.info("Laws: &7" + context.lawSummary()));
        player.sendMessage(fmt.info("Risk: &7" + context.riskSummary()));
        player.sendMessage(fmt.warn("Illegal actions are not automatically blocked. They create evidence if district law/evidence systems are active."));
        return true;
    }

    private String regionSummary(CurrentAreaContext context) {
        if (context.activeRegions().isEmpty()) {
            return "None";
        }
        return context.activeRegions().stream()
            .map(region -> region.getName() + "(" + region.getType().name() + ")")
            .collect(Collectors.joining(", "));
    }

    private String flagSummary(CurrentAreaContext context) {
        if (context.activeFlags().isEmpty()) {
            return "default allow";
        }
        return context.activeFlags().entrySet().stream()
            .map(entry -> entry.getKey().name() + "=" + entry.getValue())
            .collect(Collectors.joining(", "));
    }

    private String pretty(CurrentAreaContext.AreaType type) {
        return type.name().replace('_', ' ');
    }
}

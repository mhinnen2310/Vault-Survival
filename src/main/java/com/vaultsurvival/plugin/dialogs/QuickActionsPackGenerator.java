package com.vaultsurvival.plugin.dialogs;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class QuickActionsPackGenerator {

    private final VaultSurvivalPlugin plugin;

    public QuickActionsPackGenerator(VaultSurvivalPlugin plugin) {
        this.plugin = plugin;
    }

    public void generate() {
        if (!plugin.getConfigManager().areQuickActionsEnabled()) {
            return;
        }

        Path root = plugin.getDataFolder().toPath().resolve("generated-quick-actions-datapack");
        try {
            writePack(root);
            plugin.getLogger().info("Generated Quick Actions datapack template at " + root);
            installToWorldDatapacks();
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to generate Quick Actions datapack template: " + e.getMessage());
        }
    }

    private void installToWorldDatapacks() {
        if (!plugin.getConfigManager().shouldInstallQuickActionsDatapack()) {
            return;
        }
        World world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        if (world == null) {
            return;
        }
        Path installedRoot = Bukkit.getWorldContainer().toPath()
            .resolve(world.getName())
            .resolve("datapacks")
            .resolve("VaultSurvivalQuickActions");
        try {
            writePack(installedRoot);
            plugin.getLogger().info("Installed Quick Actions datapack at " + installedRoot + " (restart required).");
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to install Quick Actions datapack: " + e.getMessage());
        }
    }

    private void writePack(Path root) throws IOException {
        Files.createDirectories(root.resolve("data/minecraft/tags/dialog"));
        Files.createDirectories(root.resolve("data/vaultsurvival/dialog"));
        Files.writeString(root.resolve("pack.mcmeta"), packMeta(), StandardCharsets.UTF_8);
        Files.writeString(root.resolve("data/minecraft/tags/dialog/quick_actions.json"), quickActionsTag(), StandardCharsets.UTF_8);
        Files.writeString(root.resolve("data/minecraft/tags/dialog/pause_screen_additions.json"), quickActionsTag(), StandardCharsets.UTF_8);
        Files.writeString(root.resolve("data/vaultsurvival/dialog/main.json"), mainDialog(), StandardCharsets.UTF_8);
        Files.writeString(root.resolve("README.txt"), readme(), StandardCharsets.UTF_8);
    }

    private String packMeta() {
        return """
            {
              "pack": {
                "pack_format": 80,
                "description": "Vault Survival Quick Actions"
              }
            }
            """;
    }

    private String quickActionsTag() {
        return """
            {
              "replace": false,
              "values": [
                {
                  "id": "vaultsurvival:main",
                  "required": false
                }
              ]
            }
            """;
    }

    private String mainDialog() {
        return """
            {
              "type": "minecraft:multi_action",
              "title": "Vault Survival",
              "body": [
                {
                  "type": "minecraft:plain_message",
                  "contents": "Open the main Vault Survival menu."
                }
              ],
              "can_close_with_escape": true,
              "pause": false,
              "after_action": "close",
              "columns": 1,
              "actions": [
                {
                  "label": "Open Vault Survival Menu",
                  "action": {
                    "type": "run_command",
                    "command": "/vsmenu"
                  }
                }
              ]
            }
            """;
    }

    private String readme() {
        return """
            Vault Survival Quick Actions datapack template

            Copy this generated-quick-actions-datapack folder into:
              <server>/world/datapacks/VaultSurvivalQuickActions

            The plugin also tries to install this datapack automatically when:
              dialogs.quickActions.installDatapackToWorld: true

            Then restart the server. If the Minecraft dialog registry changes in your Paper/Minecraft build,
            leave this datapack out and use /quickactions or /vsmenu instead.
            """;
    }
}

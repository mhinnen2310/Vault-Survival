package com.vaultsurvival.plugin.dialogs;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;

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
            Files.createDirectories(root.resolve("data/minecraft/tags/dialog"));
            Files.createDirectories(root.resolve("data/vaultsurvival/dialog"));
            Files.writeString(root.resolve("pack.mcmeta"), packMeta(), StandardCharsets.UTF_8);
            Files.writeString(root.resolve("data/minecraft/tags/dialog/quick_actions.json"), quickActionsTag(), StandardCharsets.UTF_8);
            Files.writeString(root.resolve("data/vaultsurvival/dialog/main.json"), mainDialog(), StandardCharsets.UTF_8);
            Files.writeString(root.resolve("README.txt"), readme(), StandardCharsets.UTF_8);
            plugin.getLogger().info("Generated Quick Actions datapack template at " + root);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to generate Quick Actions datapack template: " + e.getMessage());
        }
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
                    "type": "dynamic/run_command",
                    "template": "vsmenu"
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

            Then restart the server. If the Minecraft dialog registry changes in your Paper/Minecraft build,
            leave this datapack out and use /quickactions or /vsmenu instead.
            """;
    }
}

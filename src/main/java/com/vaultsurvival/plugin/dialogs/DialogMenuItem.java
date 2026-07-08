package com.vaultsurvival.plugin.dialogs;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

public class DialogMenuItem {

    private final String label;
    private final String description;
    private final String command;
    private final String permission;
    private final Material material;
    private final boolean adminSensitive;

    public DialogMenuItem(String label, String description, String command, String permission,
                          Material material, boolean adminSensitive) {
        this.label = label;
        this.description = description;
        this.command = command;
        this.permission = permission;
        this.material = material;
        this.adminSensitive = adminSensitive;
    }

    public static DialogMenuItem item(String label, String description, String command, String permission,
                                      Material material) {
        return new DialogMenuItem(label, description, command, permission, material, false);
    }

    public static DialogMenuItem adminItem(String label, String description, String command, String permission,
                                           Material material) {
        return new DialogMenuItem(label, description, command, permission, material, true);
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public String command() {
        return command;
    }

    public String permission() {
        return permission;
    }

    public Material material() {
        return material;
    }

    public boolean adminSensitive() {
        return adminSensitive;
    }

    public boolean isAllowed(Player player) {
        return permission == null || permission.isBlank() || player.hasPermission(permission);
    }

    public List<String> lore(boolean allowed) {
        if (allowed) {
            return List.of("&7" + description, "&8Runs /" + command);
        }
        return List.of("&7" + description, "&cNo permission");
    }
}

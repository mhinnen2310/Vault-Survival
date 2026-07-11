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
    private final boolean locked;
    private final String lockedExplanation;
    private final boolean status;

    public DialogMenuItem(String label, String description, String command, String permission,
                          Material material, boolean adminSensitive) {
        this(label, description, command, permission, material, adminSensitive, false, null, false);
    }

    private DialogMenuItem(String label, String description, String command, String permission,
                           Material material, boolean adminSensitive, boolean locked,
                           String lockedExplanation, boolean status) {
        this.label = label;
        this.description = description;
        this.command = command;
        this.permission = permission;
        this.material = material;
        this.adminSensitive = adminSensitive;
        this.locked = locked;
        this.lockedExplanation = lockedExplanation;
        this.status = status;
    }

    public static DialogMenuItem item(String label, String description, String command, String permission,
                                      Material material) {
        return new DialogMenuItem(label, description, command, permission, material, false);
    }

    public static DialogMenuItem adminItem(String label, String description, String command, String permission,
                                           Material material) {
        return new DialogMenuItem(label, description, command, permission, material, true);
    }

    public static DialogMenuItem locked(String label, String description, String explanation, Material material) {
        return new DialogMenuItem(label, description, "vsmenu locked " + sanitize(explanation), null,
            material, false, true, explanation, false);
    }

    public static DialogMenuItem status(String label, String description, String value, Material material) {
        return new DialogMenuItem(label, description, "vsmenu locked " + sanitize(value), null,
            material, false, true, value, true);
    }

    public DialogMenuItem lockedCopy(String explanation) {
        return new DialogMenuItem(label, description, "vsmenu locked " + sanitize(explanation), permission,
            material, adminSensitive, true, explanation, false);
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

    public boolean locked() {
        return locked;
    }

    public String lockedExplanation() {
        return lockedExplanation;
    }
    public boolean status() { return status; }

    public boolean isAllowed(Player player) {
        return !locked && (permission == null || permission.isBlank() || player.hasPermission(permission));
    }

    public List<String> lore(boolean allowed) {
        if (allowed && !locked) {
            return List.of("&7" + description, "&8Runs /" + command);
        }
        String reason = lockedExplanation != null && !lockedExplanation.isBlank()
            ? lockedExplanation
            : "No permission";
        return status ? List.of("&7" + description, "&f" + reason)
            : List.of("&7" + description, "&cLocked", "&7" + reason);
    }

    private static String sanitize(String text) {
        return text == null ? "locked" : text.replaceAll("[^A-Za-z0-9_ .-]", "").trim();
    }
}

package com.vaultsurvival.plugin.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;

/**
 * Consistent message formatting for all plugin messages.
 * Provides methods for colorizing, prefixing, and formatting chat output.
 */
public class MessageFormatter {

    private String serverPrefix;
    private Component serverPrefixComponent;

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final LegacyComponentSerializer LEGACY_SECTION = LegacyComponentSerializer.legacySection();
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    public MessageFormatter(String serverPrefix) {
        this.serverPrefix = serverPrefix;
        this.serverPrefixComponent = LEGACY.deserialize(serverPrefix);
    }

    /**
     * Update the server prefix (called on /vs reload).
     */
    public void setPrefix(String newPrefix) {
        this.serverPrefix = newPrefix;
        this.serverPrefixComponent = LEGACY.deserialize(newPrefix);
    }

    /**
     * Format a message with the server prefix.
     */
    public String prefix(String message) {
        return ChatColor.translateAlternateColorCodes('&', serverPrefix + " &7" + message);
    }

    /**
     * Format a component with the server prefix using Adventure API.
     */
    public Component prefixComponent(Component message) {
        return Component.empty()
            .append(serverPrefixComponent)
            .append(Component.space())
            .append(Component.text("").decoration(TextDecoration.ITALIC, false))
            .append(message);
    }

    /**
     * Parse a string with & color codes to a Component.
     */
    public Component deserialize(String message) {
        return LEGACY.deserialize(message).decoration(TextDecoration.ITALIC, false);
    }

    /**
     * Parse a string with legacy & color codes to the old String format.
     */
    public String colorize(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    /**
     * Static utility: parse & color codes and return a Component with no italics.
     * Used by GUIFramework for inventory titles.
     */
    public static Component deserializeLegacy(String message) {
        return LEGACY.deserialize(message).decoration(TextDecoration.ITALIC, false);
    }

    /**
     * Parse a MiniMessage formatted string to a Component.
     */
    public Component miniMessage(String message) {
        return MINI.deserialize(message);
    }

    // --- Common message templates ---

    public String error(String message) {
        return prefix("&c" + message);
    }

    public String success(String message) {
        return prefix("&a" + message);
    }

    public String info(String message) {
        return prefix("&7" + message);
    }

    public String warn(String message) {
        return prefix("&e" + message);
    }

    public Component errorComponent(String message) {
        return prefixComponent(deserialize("&c" + message));
    }

    public Component successComponent(String message) {
        return prefixComponent(deserialize("&a" + message));
    }

    public Component infoComponent(String message) {
        return prefixComponent(deserialize("&7" + message));
    }

    /**
     * Format a monetary amount for display.
     */
    public String formatMoney(long amount, String currencyName, String currencyNamePlural) {
        if (amount == 1) {
            return "&6" + amount + " &e" + currencyName;
        }
        return "&6" + amount + " &e" + currencyNamePlural;
    }

    /**
     * Format a permission denied message.
     */
    public String permissionDenied() {
        return error("You do not have permission to do this.");
    }

    /**
     * Format a player-not-found message.
     */
    public String playerNotFound(String name) {
        return error("Player &e" + name + "&c not found.");
    }

    /**
     * Format a cooldown message.
     */
    public String cooldown(int secondsRemaining) {
        return error("You must wait &e" + secondsRemaining + "s&c before doing this again.");
    }

    /**
     * Build a header line for GUI or chat.
     */
    public String header(String title) {
        return colorize("&8&m---&r &6&l" + title + " &8&m---");
    }

    /**
     * Format a list of items with index numbers.
     */
    public String listItem(int index, String item) {
        return colorize("&8" + index + ". &7" + item);
    }
}

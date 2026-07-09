package com.vaultsurvival.plugin.chat;

import java.util.Locale;

public enum ChatChannel {
    GLOBAL("global", "Global", "&7[G]"),
    LOCAL("local", "Local", "&a[L]"),
    DISTRICT("district", "District", "&e[D]"),
    ALLY("ally", "Ally", "&d[A]"),
    POLICE("police", "Police", "&9[P]"),
    MERCHANT("merchant", "Merchant", "&6[M]"),
    STAFF("staff", "Staff", "&c[S]"),
    HELP("help", "Help", "&b[H]");

    private final String id;
    private final String displayName;
    private final String prefix;

    ChatChannel(String id, String displayName, String prefix) {
        this.id = id;
        this.displayName = displayName;
        this.prefix = prefix;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String prefix() {
        return prefix;
    }

    public static ChatChannel from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        for (ChatChannel channel : values()) {
            if (channel.id.replace("_", "").equals(normalized)
                || channel.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return channel;
            }
        }
        return switch (normalized) {
            case "g" -> GLOBAL;
            case "l" -> LOCAL;
            case "dc" -> DISTRICT;
            case "ac" -> ALLY;
            case "pc" -> POLICE;
            case "mc" -> MERCHANT;
            case "sc" -> STAFF;
            case "helpchat" -> HELP;
            default -> null;
        };
    }
}

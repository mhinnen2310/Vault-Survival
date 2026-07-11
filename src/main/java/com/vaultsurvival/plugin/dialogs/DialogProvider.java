package com.vaultsurvival.plugin.dialogs;

import org.bukkit.entity.Player;

import java.util.List;

public interface DialogProvider {

    String getName();

    boolean isAvailable();

    boolean open(Player player, DialogMenuType menuType, List<DialogMenuItem> items);

    default boolean open(Player player, DialogMenuType menuType, String title, String body, List<DialogMenuItem> items) {
        return open(player, menuType, items);
    }

    default boolean openInput(Player player, DialogInputDefinition input) {
        return false;
    }

    default boolean openVweOperation(Player player, String status, int blockCount) {
        return false;
    }

    default boolean openForm(Player player, DialogFormDefinition form) {
        return false;
    }
}

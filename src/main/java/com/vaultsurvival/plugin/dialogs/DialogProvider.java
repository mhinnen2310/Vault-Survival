package com.vaultsurvival.plugin.dialogs;

import org.bukkit.entity.Player;

import java.util.List;

public interface DialogProvider {

    String getName();

    boolean isAvailable();

    boolean open(Player player, DialogMenuType menuType, List<DialogMenuItem> items);

    default boolean openInput(Player player, DialogInputDefinition input) {
        return false;
    }
}

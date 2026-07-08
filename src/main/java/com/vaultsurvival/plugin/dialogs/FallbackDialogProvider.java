package com.vaultsurvival.plugin.dialogs;

import com.vaultsurvival.plugin.VaultSurvivalPlugin;
import com.vaultsurvival.plugin.core.GUIFramework;
import com.vaultsurvival.plugin.core.MessageFormatter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class FallbackDialogProvider implements DialogProvider {

    private final VaultSurvivalPlugin plugin;
    private final DialogService dialogService;

    public FallbackDialogProvider(VaultSurvivalPlugin plugin, DialogService dialogService) {
        this.plugin = plugin;
        this.dialogService = dialogService;
    }

    @Override
    public String getName() {
        return plugin.getConfigManager().fallbackToInventoryGui() ? "inventory-gui" : "clickable-chat";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public boolean open(Player player, DialogMenuType menuType, List<DialogMenuItem> items) {
        if (plugin.getConfigManager().fallbackToInventoryGui()) {
            openInventory(player, menuType, items);
        } else {
            openClickableChat(player, menuType, items);
        }
        return true;
    }

    private void openInventory(Player player, DialogMenuType menuType, List<DialogMenuItem> items) {
        List<GUIFramework.GUIItem> guiItems = new ArrayList<>();
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 20, 21, 22, 23, 24, 25};
        int index = 0;

        for (DialogMenuItem item : items) {
            if (index >= slots.length) {
                break;
            }
            boolean allowed = item.isAllowed(player);
            Material material = allowed ? item.material() : Material.GRAY_DYE;
            guiItems.add(GUIFramework.GUIItem.button(slots[index++], material,
                (allowed ? "&6" : "&8") + item.label(),
                item.lore(allowed),
                (p, e) -> {
                    if (item.isAllowed(p)) {
                        p.closeInventory();
                        dialogService.runItemCommand(p, item);
                    } else {
                        p.sendMessage(plugin.getMessageFormatter().permissionDenied());
                    }
                }));
        }

        guiItems.add(GUIFramework.GUIItem.closeButton(4));
        plugin.getGuiFramework().openGUI(player, "&6" + menuType.title(), 4, guiItems);
    }

    private void openClickableChat(Player player, DialogMenuType menuType, List<DialogMenuItem> items) {
        MessageFormatter fmt = plugin.getMessageFormatter();
        player.sendMessage(fmt.header(menuType.title()));
        player.sendMessage(fmt.info(menuType.body()));
        for (DialogMenuItem item : items) {
            if (!item.isAllowed(player)) {
                continue;
            }
            player.sendMessage(Component.text("- " + item.label())
                .clickEvent(ClickEvent.runCommand("/" + item.command()))
                .hoverEvent(Component.text(item.description())));
        }
    }
}

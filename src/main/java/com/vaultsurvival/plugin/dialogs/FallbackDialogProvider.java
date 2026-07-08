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
        int[] slots = {10, 12, 14, 16, 19, 21, 23, 25};
        int index = 0;
        DialogMenuItem back = null;

        for (DialogMenuItem item : items) {
            if (isBack(item)) {
                back = item;
                continue;
            }
            if (index >= slots.length) {
                break;
            }
            boolean allowed = true;
            Material material = item.material();
            guiItems.add(GUIFramework.GUIItem.button(slots[index++], material,
                "&6" + item.label(),
                item.lore(allowed),
                (p, e) -> {
                    p.closeInventory();
                    dialogService.runItemCommand(p, item);
                }));
        }

        if (back != null) {
            DialogMenuItem backItem = back;
            guiItems.add(GUIFramework.GUIItem.button(27, backItem.material(), "&6" + backItem.label(),
                backItem.lore(true),
                (p, e) -> {
                    p.closeInventory();
                    dialogService.runItemCommand(p, backItem);
                }));
        }
        guiItems.add(GUIFramework.GUIItem.closeButton(35));
        plugin.getGuiFramework().openGUI(player, "&6" + menuType.title(), 4, guiItems);
    }

    private void openClickableChat(Player player, DialogMenuType menuType, List<DialogMenuItem> items) {
        MessageFormatter fmt = plugin.getMessageFormatter();
        player.sendMessage(fmt.header(menuType.title()));
        player.sendMessage(fmt.info(menuType.body()));
        for (DialogMenuItem item : items) {
            player.sendMessage(Component.text("- " + item.label())
                .clickEvent(ClickEvent.runCommand("/" + item.command()))
                .hoverEvent(Component.text(item.description())));
        }
    }

    @Override
    public boolean openInput(Player player, DialogInputDefinition input) {
        MessageFormatter fmt = plugin.getMessageFormatter();
        player.sendMessage(fmt.header(input.title()));
        player.sendMessage(fmt.info(input.description()));
        player.sendMessage(Component.text("Type " + input.exampleCommand())
            .clickEvent(ClickEvent.suggestCommand(input.exampleCommand().replace("<value>", "")))
            .hoverEvent(Component.text("Click to put the command in chat.")));
        return true;
    }

    private boolean isBack(DialogMenuItem item) {
        return "Back".equalsIgnoreCase(item.label()) && "vsmenu".equalsIgnoreCase(item.command());
    }
}

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
        return open(player, menuType, menuType.title(), menuType.body(), items);
    }

    @Override
    public boolean open(Player player, DialogMenuType menuType, String title, String body, List<DialogMenuItem> items) {
        if (plugin.getConfigManager().fallbackToInventoryGui()) {
            openInventory(player, title, items);
        } else {
            openClickableChat(player, title, body, items);
        }
        return true;
    }

    private void openInventory(Player player, String title, List<DialogMenuItem> items) {
        List<GUIFramework.GUIItem> guiItems = new ArrayList<>();
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        int index = 0;
        DialogMenuItem back = null;
        DialogMenuItem home = null;

        for (DialogMenuItem item : items) {
            if (isBack(item)) {
                back = item;
                continue;
            }
            if (isHome(item)) {
                home = item;
                continue;
            }
            if (isClose(item)) {
                continue;
            }
            if (index >= slots.length) {
                break;
            }
            boolean allowed = !item.locked();
            Material material = item.material();
            guiItems.add(GUIFramework.GUIItem.button(slots[index++], material,
                (allowed ? "&6" : "&8") + item.label(),
                item.lore(allowed),
                (p, e) -> {
                    dialogService.runItemCommand(p, item);
                }));
        }

        if (back != null) {
            DialogMenuItem backItem = back;
            guiItems.add(GUIFramework.GUIItem.button(27, backItem.material(), "&6" + backItem.label(),
                backItem.lore(true),
                (p, e) -> {
                    dialogService.runItemCommand(p, backItem);
                }));
        }
        if (home != null) {
            DialogMenuItem homeItem = home;
            guiItems.add(GUIFramework.GUIItem.button(31, homeItem.material(), "&6" + homeItem.label(),
                homeItem.lore(true),
                (p, e) -> {
                    dialogService.runItemCommand(p, homeItem);
                }));
        }
        guiItems.add(GUIFramework.GUIItem.closeButton(35));
        plugin.getGuiFramework().openGUI(player, "&6" + title, 4, guiItems);
    }

    private void openClickableChat(Player player, String title, String body, List<DialogMenuItem> items) {
        MessageFormatter fmt = plugin.getMessageFormatter();
        player.sendMessage(fmt.header(title));
        player.sendMessage(fmt.info(body));
        for (DialogMenuItem item : items) {
            String prefix = item.locked() && !item.status() ? "- [Locked] " : "- ";
            player.sendMessage(Component.text(prefix + item.label())
                .clickEvent(ClickEvent.runCommand("/" + item.command()))
                .hoverEvent(Component.text(item.locked() ? item.lockedExplanation() : item.description())));
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

    @Override
    public boolean openForm(Player player, DialogFormDefinition form) {
        open(player, DialogMenuType.MAIN, form.title(),
            form.body() + "\n\nNative controls are unavailable on this server/client. No values were changed.",
            List.of(DialogMenuItem.item("Back", "Return without changing settings.",
                form.cancelCommand(), null, Material.ARROW)));
        return true;
    }

    private boolean isBack(DialogMenuItem item) {
        return "Back".equalsIgnoreCase(item.label());
    }

    private boolean isHome(DialogMenuItem item) {
        return "Home".equalsIgnoreCase(item.label());
    }

    private boolean isClose(DialogMenuItem item) {
        return "Close".equalsIgnoreCase(item.label());
    }
}

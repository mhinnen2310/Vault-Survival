package com.vaultsurvival.plugin.dialogs;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class NativePaperDialogProvider implements DialogProvider {

    private final boolean available;

    public NativePaperDialogProvider() {
        this.available = hasClass("io.papermc.paper.dialog.Dialog")
            && hasClass("net.kyori.adventure.dialog.DialogLike");
    }

    @Override
    public String getName() {
        return "native-paper-dialog";
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public boolean open(Player player, DialogMenuType menuType, List<DialogMenuItem> items) {
        if (!available) {
            return false;
        }

        List<ActionButton> buttons = new ArrayList<>();
        for (DialogMenuItem item : items) {
            if (!item.isAllowed(player)) {
                continue;
            }
            buttons.add(ActionButton.builder(Component.text(item.label()))
                .tooltip(Component.text(item.description()))
                .width(200)
                .action(DialogAction.staticAction(ClickEvent.runCommand("/" + item.command())))
                .build());
        }

        ActionButton exit = ActionButton.builder(Component.text("Close"))
            .tooltip(Component.text("Close this menu"))
            .width(200)
            .build();

        Dialog dialog = Dialog.create(builder -> builder.empty()
            .base(DialogBase.builder(Component.text(menuType.title()))
                .body(List.of(DialogBody.plainMessage(Component.text(menuType.body()), 300)))
                .canCloseWithEscape(true)
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .build())
            .type(DialogType.multiAction(buttons, exit, 2)));

        player.showDialog(dialog);
        return true;
    }

    private boolean hasClass(String name) {
        try {
            Class.forName(name, false, getClass().getClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }
}

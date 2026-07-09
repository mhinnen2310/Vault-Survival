package com.vaultsurvival.plugin.dialogs;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
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
        return open(player, menuType, menuType.title(), menuType.body(), items);
    }

    @Override
    public boolean open(Player player, DialogMenuType menuType, String title, String body, List<DialogMenuItem> items) {
        if (!available) {
            return false;
        }

        List<ActionButton> buttons = new ArrayList<>();
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
            buttons.add(button(item));
        }
        if (buttons.size() % 2 != 0) {
            buttons.add(spacer());
        }
        if (back != null) {
            buttons.add(button(back));
            buttons.add(home != null ? button(home) : spacer());
        } else if (home != null) {
            buttons.add(button(home));
            buttons.add(spacer());
        }

        ActionButton exit = ActionButton.builder(Component.text("Close"))
            .tooltip(Component.text("Close this menu"))
            .width(200)
            .build();

        Dialog dialog = Dialog.create(builder -> builder.empty()
            .base(DialogBase.builder(Component.text(title))
                .body(List.of(DialogBody.plainMessage(Component.text(body), 300)))
                .canCloseWithEscape(true)
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.NONE)
                .build())
            .type(DialogType.multiAction(buttons, exit, 2)));

        player.showDialog(dialog);
        return true;
    }

    private ActionButton button(DialogMenuItem item) {
        String label = item.locked() ? "[Locked] " + item.label() : item.label();
        String tooltip = item.locked() ? item.lockedExplanation() : item.description();
        return ActionButton.builder(Component.text(label))
            .tooltip(Component.text(tooltip == null ? "" : tooltip))
            .width(200)
            .action(DialogAction.staticAction(ClickEvent.runCommand("/" + item.command())))
            .build();
    }

    private ActionButton spacer() {
        return ActionButton.builder(Component.text(" "))
            .tooltip(Component.text(" "))
            .width(200)
            .build();
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

    @Override
    public boolean openInput(Player player, DialogInputDefinition input) {
        if (!available) {
            return false;
        }

        ActionButton submit = ActionButton.builder(Component.text("Run"))
            .tooltip(Component.text(input.exampleCommand()))
            .width(200)
            .action(DialogAction.commandTemplate(input.commandTemplate()))
            .build();

        Dialog dialog = Dialog.create(builder -> builder.empty()
            .base(DialogBase.builder(Component.text(input.title()))
                .body(List.of(DialogBody.plainMessage(Component.text(input.description()), 300)))
                .inputs(List.of(DialogInput.text("value", Component.text(input.label()))
                    .width(300)
                    .maxLength(256)
                    .build()))
                .canCloseWithEscape(true)
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .build())
            .type(DialogType.notice(submit)));

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

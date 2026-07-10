package com.vaultsurvival.plugin.dialogs;
import java.util.List;
public record DialogResult(DialogResultType type, String title, String message, List<DialogMenuItem> actions) {
    public DialogResult { actions = actions == null ? List.of() : List.copyOf(actions); }
}

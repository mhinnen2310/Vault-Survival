package com.vaultsurvival.plugin.dialogs;
import java.util.List;
public record DialogError(String title, String message) {
    public DialogResult result() { return new DialogResult(DialogResultType.ERROR, title, message, List.of()); }
}

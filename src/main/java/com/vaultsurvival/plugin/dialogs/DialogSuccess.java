package com.vaultsurvival.plugin.dialogs;
import java.util.List;
public record DialogSuccess(String title, String message) {
    public DialogResult result() { return new DialogResult(DialogResultType.SUCCESS, title, message, List.of()); }
}

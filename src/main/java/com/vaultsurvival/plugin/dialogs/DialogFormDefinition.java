package com.vaultsurvival.plugin.dialogs;

import java.util.List;

/** Service-created form definition; providers only decide how to render it. */
public record DialogFormDefinition(
    String title,
    String body,
    List<DialogFormField> fields,
    String submitLabel,
    String commandTemplate,
    String cancelCommand
) {
    public DialogFormDefinition {
        fields = fields == null ? List.of() : List.copyOf(fields);
        submitLabel = submitLabel == null || submitLabel.isBlank() ? "Save" : submitLabel;
        cancelCommand = cancelCommand == null || cancelCommand.isBlank() ? "vsmenu" : cancelCommand;
    }
}

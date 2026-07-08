package com.vaultsurvival.plugin.dialogs;

public record DialogInputDefinition(
    String id,
    String title,
    String description,
    String label,
    String commandTemplate,
    String permission,
    boolean adminSensitive
) {
    public String exampleCommand() {
        return "/" + commandTemplate.replace("$(value)", "<value>");
    }
}

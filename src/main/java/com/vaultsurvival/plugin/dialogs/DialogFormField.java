package com.vaultsurvival.plugin.dialogs;

import java.util.List;

/** One typed input rendered by a native Paper dialog. */
public record DialogFormField(
    String key,
    String label,
    Type type,
    String initial,
    List<Option> options,
    float minimum,
    float maximum,
    float step,
    int maxLength
) {
    public enum Type { TOGGLE, DROPDOWN, SLIDER, NUMBER, TEXT, MULTILINE }
    public record Option(String value, String label) { }

    public DialogFormField {
        options = options == null ? List.of() : List.copyOf(options);
        initial = initial == null ? "" : initial;
        maxLength = Math.max(1, maxLength);
    }

    public static DialogFormField toggle(String key, String label, boolean initial) {
        return new DialogFormField(key, label, Type.TOGGLE, Boolean.toString(initial), List.of(), 0, 1, 1, 5);
    }

    public static DialogFormField dropdown(String key, String label, String initial, List<Option> options) {
        return new DialogFormField(key, label, Type.DROPDOWN, initial, options, 0, 0, 0, 64);
    }

    public static DialogFormField slider(String key, String label, float initial, float minimum, float maximum, float step) {
        return new DialogFormField(key, label, Type.SLIDER, Float.toString(initial), List.of(), minimum, maximum, step, 32);
    }

    public static DialogFormField number(String key, String label, long initial) {
        return new DialogFormField(key, label, Type.NUMBER, Long.toString(initial), List.of(), 0, 0, 0, 20);
    }

    public static DialogFormField text(String key, String label, String initial, int maxLength, boolean multiline) {
        return new DialogFormField(key, label, multiline ? Type.MULTILINE : Type.TEXT, initial, List.of(), 0, 0, 0, maxLength);
    }
}

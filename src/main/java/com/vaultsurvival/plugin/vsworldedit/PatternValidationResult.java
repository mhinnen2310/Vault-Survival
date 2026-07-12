package com.vaultsurvival.plugin.vsworldedit;

/** Parser result that carries a complete player-facing validation error when invalid. */
public record PatternValidationResult(boolean valid, BlockPattern pattern, String error, String suggestion) {
    public static PatternValidationResult valid(BlockPattern pattern) {
        return new PatternValidationResult(true, pattern, null, null);
    }
    public static PatternValidationResult invalid(String error) {
        return new PatternValidationResult(false, null, error, null);
    }
    public static PatternValidationResult invalid(String error, String suggestion) {
        return new PatternValidationResult(false, null, error, suggestion);
    }
}

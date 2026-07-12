package com.vaultsurvival.plugin.dialogs;
import java.util.Optional;
public final class DialogFormValidationService {
    public Optional<String> required(String value, String label) { return value == null || value.isBlank() ? Optional.of(label + " is required.") : Optional.empty(); }
    public Optional<String> boundedLong(String value, String label, long min, long max) { try { long n = Long.parseLong(value); return n < min || n > max ? Optional.of(label + " must be between " + min + " and " + max + ".") : Optional.empty(); } catch (Exception e) { return Optional.of(label + " must be a whole number."); } }
    public <E extends Enum<E>> Optional<String> enumValue(String value, String label, Class<E> type) { try { Enum.valueOf(type, value.toUpperCase()); return Optional.empty(); } catch (Exception e) { return Optional.of("Unknown " + label + "."); } }
}

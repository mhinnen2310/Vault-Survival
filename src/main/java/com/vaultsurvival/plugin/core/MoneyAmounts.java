package com.vaultsurvival.plugin.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/** Shared, overflow-safe parsing and compact display for physical-cash amounts. */
public final class MoneyAmounts {
    private MoneyAmounts() { }

    public static long parse(String input) {
        if (input == null) throw new NumberFormatException("Missing amount");
        String value = input.trim().toLowerCase(Locale.ROOT).replace("_", "");
        if (value.isEmpty()) throw new NumberFormatException("Missing amount");
        long multiplier = 1L;
        char suffix = value.charAt(value.length() - 1);
        if (suffix == 'k' || suffix == 'm' || suffix == 'b') {
            multiplier = suffix == 'k' ? 1_000L : suffix == 'm' ? 1_000_000L : 1_000_000_000L;
            value = value.substring(0, value.length() - 1);
        }
        BigDecimal result = new BigDecimal(value).multiply(BigDecimal.valueOf(multiplier));
        return result.setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    }

    public static String compact(long amount) {
        long absolute = amount == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(amount);
        if (absolute < 1_000) return Long.toString(amount);
        long divisor = absolute >= 1_000_000_000L ? 1_000_000_000L
            : absolute >= 1_000_000L ? 1_000_000L : 1_000L;
        String suffix = divisor == 1_000_000_000L ? "b" : divisor == 1_000_000L ? "m" : "k";
        BigDecimal compact = BigDecimal.valueOf(amount).divide(BigDecimal.valueOf(divisor), 2, RoundingMode.DOWN)
            .stripTrailingZeros();
        return compact.toPlainString() + suffix;
    }
}

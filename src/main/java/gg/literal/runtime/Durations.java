package gg.literal.runtime;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses short durations such as {@code 30s}, {@code 15m}, {@code 12h}, {@code 7d} or {@code 2w}. */
public final class Durations {

    private static final Pattern DURATION = Pattern.compile("(\\d+)([smhdw])");

    private Durations() {
    }

    /** Returns the duration in milliseconds, or {@code -1} if {@code token} is not a valid duration. */
    public static long parseMillis(final String token) {
        if (token == null) {
            return -1L;
        }
        final Matcher m = DURATION.matcher(token.trim().toLowerCase(Locale.ROOT));
        if (!m.matches()) {
            return -1L;
        }
        final long amount;
        try {
            amount = Long.parseLong(m.group(1));
        } catch (NumberFormatException ex) {
            return -1L;
        }
        final long unit = switch (m.group(2).charAt(0)) {
            case 's' -> 1_000L;
            case 'm' -> 60_000L;
            case 'h' -> 3_600_000L;
            case 'd' -> 86_400_000L;
            default -> 604_800_000L;
        };
        try {
            return Math.multiplyExact(amount, unit);
        } catch (ArithmeticException ex) {
            return -1L;
        }
    }
}

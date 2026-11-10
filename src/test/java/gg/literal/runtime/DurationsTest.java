package gg.literal.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DurationsTest {

    @Test
    void parsesEveryUnit() {
        assertEquals(30_000L, Durations.parseMillis("30s"));
        assertEquals(15 * 60_000L, Durations.parseMillis("15m"));
        assertEquals(12 * 3_600_000L, Durations.parseMillis("12h"));
        assertEquals(7 * 86_400_000L, Durations.parseMillis("7d"));
        assertEquals(2 * 604_800_000L, Durations.parseMillis("2w"));
    }

    @Test
    void isCaseAndWhitespaceTolerant() {
        assertEquals(60_000L, Durations.parseMillis(" 1M "));
    }

    @Test
    void rejectsGarbage() {
        assertEquals(-1L, Durations.parseMillis("5x"));
        assertEquals(-1L, Durations.parseMillis("m5"));
        assertEquals(-1L, Durations.parseMillis(""));
        assertEquals(-1L, Durations.parseMillis(null));
        assertEquals(-1L, Durations.parseMillis("-3h"));
    }

    @Test
    void overflowIsRejectedInsteadOfWrapping() {
        assertEquals(-1L, Durations.parseMillis("9999999999999999w"));
    }
}

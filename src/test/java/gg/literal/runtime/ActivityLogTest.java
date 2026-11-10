package gg.literal.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class ActivityLogTest {

    @Test
    void recordsEventsInOrderWithIncreasingSequenceNumbers() {
        final ActivityLog log = new ActivityLog(10);
        log.record("JOIN", "Alex", "1.2.3.4");
        log.record("LEAVE", "Alex", "");
        final List<ActivityLog.Event> events = log.since(0, 100);
        assertEquals(2, events.size());
        assertEquals("JOIN", events.get(0).type());
        assertEquals(1, events.get(0).seq());
        assertEquals(2, events.get(1).seq());
        assertEquals(2, log.latestSeq());
    }

    @Test
    void sinceOnlyReturnsNewerEvents() {
        final ActivityLog log = new ActivityLog(10);
        for (int i = 0; i < 5; i++) {
            log.record("X", "s" + i, "");
        }
        final List<ActivityLog.Event> events = log.since(3, 100);
        assertEquals(List.of(4L, 5L), events.stream().map(ActivityLog.Event::seq).toList());
    }

    @Test
    void dropsTheOldestWhenFull() {
        final ActivityLog log = new ActivityLog(3);
        for (int i = 1; i <= 5; i++) {
            log.record("X", "s" + i, "");
        }
        assertEquals(List.of(3L, 4L, 5L), log.since(0, 100).stream().map(ActivityLog.Event::seq).toList());
    }

    @Test
    void maxKeepsTheNewestEvents() {
        final ActivityLog log = new ActivityLog(10);
        for (int i = 1; i <= 6; i++) {
            log.record("X", "s" + i, "");
        }
        assertEquals(List.of(5L, 6L), log.since(0, 2).stream().map(ActivityLog.Event::seq).toList());
    }

    @Test
    void listenersSeeEveryEventAndCannotBreakRecording() {
        final ActivityLog log = new ActivityLog(5);
        final List<String> seen = new java.util.ArrayList<>();
        log.addListener(e -> { throw new IllegalStateException("broken listener"); });
        log.addListener(e -> seen.add(e.type() + ":" + e.subject()));

        log.record("JOIN", "Alex", "1.2.3.4");
        log.record("LEAVE", "Alex", "");

        assertEquals(List.of("JOIN:Alex", "LEAVE:Alex"), seen);
        assertEquals(2, log.since(0, 10).size());
    }

    @Test
    void nullTextBecomesEmpty() {
        final ActivityLog log = new ActivityLog(2);
        log.record("X", null, null);
        assertEquals("", log.since(0, 1).get(0).subject());
        assertEquals("", log.since(0, 1).get(0).detail());
    }
}

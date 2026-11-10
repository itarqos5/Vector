package gg.literal.runtime;

import gg.literal.log.TerminalLogger;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Logs at most one warning per interval and reports how many were suppressed, so floods can't spam the console. */
final class ThrottledLog {

    private final long intervalMs;
    private final AtomicLong lastLog = new AtomicLong();
    private final AtomicInteger suppressed = new AtomicInteger();

    ThrottledLog(final long intervalMs) {
        this.intervalMs = intervalMs;
    }

    void warn(final String message) {
        final long now = System.currentTimeMillis();
        final long last = lastLog.get();
        if (now - last >= intervalMs && lastLog.compareAndSet(last, now)) {
            final int skipped = suppressed.getAndSet(0);
            TerminalLogger.warn(skipped > 0 ? message + " (+" + skipped + " similar suppressed)" : message);
        } else {
            suppressed.incrementAndGet();
        }
    }
}

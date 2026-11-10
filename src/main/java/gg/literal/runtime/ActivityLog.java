package gg.literal.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A bounded, in-memory feed of notable events (players joining and leaving, backends going up and down,
 * administrative actions) with sequence numbers so a UI can fetch only what is new.
 */
public final class ActivityLog {

    public static final ActivityLog INSTANCE = new ActivityLog(500);

    /** @param type short upper-case category such as JOIN, LEAVE, BACKEND_DOWN, BAN */
    public record Event(long seq, long timeMillis, String type, String subject, String detail) {
    }

    private final int capacity;
    private final Deque<Event> events;
    private long nextSeq = 1;

    ActivityLog(final int capacity) {
        this.capacity = capacity;
        this.events = new ArrayDeque<>(capacity);
    }

    public synchronized void record(final String type, final String subject, final String detail) {
        if (events.size() >= capacity) {
            events.removeFirst();
        }
        events.addLast(new Event(nextSeq++, System.currentTimeMillis(), type, subject == null ? "" : subject,
            detail == null ? "" : detail));
    }

    /** Events with a sequence number above {@code afterSeq}, oldest first, at most {@code max} (the newest ones). */
    public synchronized List<Event> since(final long afterSeq, final int max) {
        final List<Event> out = new ArrayList<>();
        for (final Event event : events) {
            if (event.seq() > afterSeq) {
                out.add(event);
            }
        }
        return out.size() > max ? new ArrayList<>(out.subList(out.size() - max, out.size())) : out;
    }

    public synchronized long latestSeq() {
        return nextSeq - 1;
    }
}

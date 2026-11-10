package gg.literal.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Samples the global counters every {@value #INTERVAL_MS} ms into a ring buffer of per-second rates, so a
 * UI that opens later can still draw the last few minutes.
 */
public final class MetricsHistory {

    /** One sample. Rates are per second over the interval that ended at {@code timeMillis}. */
    public record Point(
        long seq,
        long timeMillis,
        int activeConnections,
        double newConnectionsPerSec,
        double bytesInPerSec,
        double bytesOutPerSec,
        double blockedPerSec,
        double statusPerSec
    ) {
    }

    static final long INTERVAL_MS = 2_000L;
    private static final int CAPACITY = 300; // 10 minutes

    private final ConnectionRegistry registry;
    private final Deque<Point> points = new ArrayDeque<>(CAPACITY);
    private long nextSeq = 1;

    private long lastTime;
    private long lastConnections;
    private long lastIn;
    private long lastOut;
    private long lastBlocked;
    private long lastStatus;

    public MetricsHistory(final ConnectionRegistry registry) {
        this.registry = registry;
    }

    public void start(final ScheduledExecutorService scheduler) {
        final Metrics m = Metrics.INSTANCE;
        lastTime = System.currentTimeMillis();
        lastConnections = m.connectionsTotal.sum();
        lastIn = m.bytesFromClients.sum();
        lastOut = m.bytesToClients.sum();
        lastBlocked = blocked(m);
        lastStatus = status(m);
        scheduler.scheduleAtFixedRate(this::sample, INTERVAL_MS, INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /** Samples with a sequence number greater than {@code afterSeq}, oldest first. */
    public synchronized List<Point> since(final long afterSeq) {
        final List<Point> out = new ArrayList<>();
        for (final Point point : points) {
            if (point.seq() > afterSeq) {
                out.add(point);
            }
        }
        return out;
    }

    private synchronized void sample() {
        final Metrics m = Metrics.INSTANCE;
        final long now = System.currentTimeMillis();
        final double seconds = Math.max(0.001, (now - lastTime) / 1000.0);

        final long connections = m.connectionsTotal.sum();
        final long in = m.bytesFromClients.sum();
        final long out = m.bytesToClients.sum();
        final long blocked = blocked(m);
        final long status = status(m);

        if (points.size() >= CAPACITY) {
            points.removeFirst();
        }
        points.addLast(new Point(
            nextSeq++,
            now,
            registry.size(),
            (connections - lastConnections) / seconds,
            (in - lastIn) / seconds,
            (out - lastOut) / seconds,
            (blocked - lastBlocked) / seconds,
            (status - lastStatus) / seconds
        ));

        lastTime = now;
        lastConnections = connections;
        lastIn = in;
        lastOut = out;
        lastBlocked = blocked;
        lastStatus = status;
    }

    private static long blocked(final Metrics m) {
        return m.blockedTotal();
    }

    private static long status(final Metrics m) {
        return m.statusFromCache.sum() + m.statusOffline.sum() + m.statusFromBackend.sum();
    }
}

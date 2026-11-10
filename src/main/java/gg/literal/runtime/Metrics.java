package gg.literal.runtime;

import java.util.concurrent.atomic.LongAdder;

/** Process-wide counters, exposed through the console {@code stats} command and the metrics endpoint. */
public final class Metrics {

    public static final Metrics INSTANCE = new Metrics();

    public final long startedAtMillis = System.currentTimeMillis();

    public final LongAdder connectionsTotal = new LongAdder();
    public final LongAdder bytesFromClients = new LongAdder();
    public final LongAdder bytesToClients = new LongAdder();

    public final LongAdder blockedBan = new LongAdder();
    public final LongAdder blockedConnectionLimit = new LongAdder();
    public final LongAdder blockedConnectionRate = new LongAdder();
    public final LongAdder blockedPacketLimit = new LongAdder();
    public final LongAdder blockedGlobalLimit = new LongAdder();
    public final LongAdder blockedMaintenance = new LongAdder();
    public final LongAdder blockedProtocol = new LongAdder();
    public final LongAdder blockedHost = new LongAdder();
    public final LongAdder blockedWhitelist = new LongAdder();

    public final LongAdder statusFromCache = new LongAdder();
    public final LongAdder statusOffline = new LongAdder();
    public final LongAdder statusFromBackend = new LongAdder();

    public final LongAdder noBackendAvailable = new LongAdder();

    /** IPs banned automatically after repeated violations. */
    public final LongAdder autoBans = new LongAdder();

    /** Connections closed for being idle or for not completing the handshake in time. */
    public final LongAdder timeouts = new LongAdder();

    private final java.util.concurrent.atomic.AtomicInteger peakConnections = new java.util.concurrent.atomic.AtomicInteger();
    private volatile long peakConnectionsAt;

    /** Records the current number of open connections; remembers the highest value seen and when. */
    public void observeConnections(final int open) {
        final int previous = peakConnections.getAndAccumulate(open, Math::max);
        if (open > previous) {
            peakConnectionsAt = System.currentTimeMillis();
        }
    }

    public int peakConnections() {
        return peakConnections.get();
    }

    /** Epoch millis at which the peak was reached, or 0 if no connection has been seen. */
    public long peakConnectionsAt() {
        return peakConnectionsAt;
    }

    /** Sum of every refusal counter, for rate charts. */
    public long blockedTotal() {
        return blockedBan.sum() + blockedConnectionLimit.sum() + blockedConnectionRate.sum()
            + blockedPacketLimit.sum() + blockedGlobalLimit.sum() + blockedWhitelist.sum() + blockedMaintenance.sum()
            + blockedProtocol.sum() + blockedHost.sum();
    }

    private Metrics() {
    }
}

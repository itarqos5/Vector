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
    public final LongAdder blockedWhitelist = new LongAdder();

    public final LongAdder statusFromCache = new LongAdder();
    public final LongAdder statusOffline = new LongAdder();
    public final LongAdder statusFromBackend = new LongAdder();

    public final LongAdder noBackendAvailable = new LongAdder();

    /** Connections closed for being idle or for not completing the handshake in time. */
    public final LongAdder timeouts = new LongAdder();

    /** Sum of every refusal counter, for rate charts. */
    public long blockedTotal() {
        return blockedBan.sum() + blockedConnectionLimit.sum() + blockedConnectionRate.sum()
            + blockedPacketLimit.sum() + blockedGlobalLimit.sum() + blockedWhitelist.sum();
    }

    private Metrics() {
    }
}

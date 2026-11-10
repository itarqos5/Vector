package gg.literal.runtime;

import gg.literal.config.VectorConfig;

import java.util.concurrent.ConcurrentHashMap;

/** Tracks concurrent connections and new-connection rate per client IP. */
public final class ConnectionLimiter {

    public enum Result { OK, TOO_MANY_CONNECTIONS, TOO_FAST }

    private static final class Counter {
        int active;
        long windowStart;
        int windowCount;
    }

    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();

    /**
     * Registers a new connection from {@code ip} unless a limit is hit. A caller that gets
     * {@link Result#OK} must call {@link #release} exactly once when the connection ends.
     */
    public Result tryAcquire(final String ip, final VectorConfig.Limits limits) {
        final int maxActive = limits.maxConnectionsPerIp();
        final int maxRate = limits.maxConnectionRatePerIp();
        if (maxActive <= 0 && maxRate <= 0) {
            // Still count so that enabling limits via reload sees accurate numbers.
            counters.compute(ip, (k, c) -> {
                final Counter counter = c == null ? new Counter() : c;
                counter.active++;
                return counter;
            });
            return Result.OK;
        }

        final long now = System.currentTimeMillis();
        final long window = limits.connectionRateWindowMs();
        final Result[] result = {Result.OK};
        counters.compute(ip, (k, c) -> {
            final Counter counter = c == null ? new Counter() : c;
            if (now - counter.windowStart >= window) {
                counter.windowStart = now;
                counter.windowCount = 0;
            }
            if (maxRate > 0 && counter.windowCount >= maxRate) {
                result[0] = Result.TOO_FAST;
            } else if (maxActive > 0 && counter.active >= maxActive) {
                result[0] = Result.TOO_MANY_CONNECTIONS;
            } else {
                counter.windowCount++;
                counter.active++;
            }
            return counter;
        });
        return result[0];
    }

    public void release(final String ip) {
        counters.computeIfPresent(ip, (k, c) -> {
            c.active = Math.max(0, c.active - 1);
            return c;
        });
    }

    /** Drops idle counters so the map does not grow with every IP ever seen. */
    public void sweep(final int windowMs) {
        final long now = System.currentTimeMillis();
        for (final String ip : counters.keySet()) {
            counters.computeIfPresent(ip, (k, c) -> c.active == 0 && now - c.windowStart >= windowMs ? null : c);
        }
    }

    public int trackedIps() {
        return counters.size();
    }
}

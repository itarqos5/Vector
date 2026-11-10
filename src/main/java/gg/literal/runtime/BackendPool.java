package gg.literal.runtime;

import gg.literal.config.VectorConfig;
import gg.literal.log.TerminalLogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** The set of configured backends, their health, and the load-balancing policy that picks between them. */
public final class BackendPool {

    private final ConfigHolder config;
    private volatile List<Backend> backends = List.of();
    private volatile VectorConfig.BalanceMode mode = VectorConfig.BalanceMode.ROUND_ROBIN;

    private final AtomicInteger roundRobin = new AtomicInteger();
    private final Object weightedLock = new Object();

    public BackendPool(final ConfigHolder config) {
        this.config = config;
        apply(config.get().backends());
    }

    /**
     * Replaces the backend list. Backends whose host:port is unchanged keep their health and connection
     * counters; removed backends simply stop receiving new connections (existing sessions finish normally).
     */
    public synchronized void apply(final VectorConfig.Backends settings) {
        final Map<String, Backend> existing = new HashMap<>();
        for (final Backend backend : backends) {
            existing.put(key(backend.host(), backend.port()), backend);
        }

        final List<Backend> next = new ArrayList<>();
        for (final VectorConfig.BackendTarget target : settings.targets()) {
            Backend backend = existing.get(key(target.host(), target.port()));
            if (backend == null) {
                backend = new Backend(target.name(), target.host(), target.port(), target.weight());
            } else {
                backend.update(target.name(), target.weight());
            }
            next.add(backend);
        }
        this.mode = settings.mode();
        this.backends = List.copyOf(next);
    }

    public List<Backend> all() {
        return backends;
    }

    public VectorConfig.BalanceMode mode() {
        return mode;
    }

    public int healthyCount() {
        int count = 0;
        for (final Backend backend : backends) {
            if (backend.healthy()) {
                count++;
            }
        }
        return count;
    }

    public Backend find(final String host, final int port) {
        for (final Backend backend : backends) {
            if (backend.port() == port && backend.host().equalsIgnoreCase(host)) {
                return backend;
            }
        }
        return null;
    }

    public Backend findByName(final String name) {
        for (final Backend backend : backends) {
            if (backend.name().equalsIgnoreCase(name)) {
                return backend;
            }
        }
        return null;
    }

    /**
     * Picks a healthy backend for a new player connection.
     *
     * @param clientIp used by the {@code ip-hash} policy
     * @param excluded backends that already failed for this connection
     * @return the chosen backend with its connection slot already acquired, or {@code null} if none is available
     */
    public Backend select(final String clientIp, final Set<Backend> excluded) {
        final List<Backend> candidates = new ArrayList<>();
        for (final Backend backend : backends) {
            if (backend.healthy() && !excluded.contains(backend)) {
                candidates.add(backend);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }

        final Backend chosen = switch (mode) {
            case ROUND_ROBIN -> candidates.get(Math.floorMod(roundRobin.getAndIncrement(), candidates.size()));
            case LEAST_CONNECTIONS -> leastConnections(candidates);
            case WEIGHTED -> weighted(candidates);
            case IP_HASH -> candidates.get(Math.floorMod(clientIp.hashCode(), candidates.size()));
        };
        chosen.acquire();
        return chosen;
    }

    /** Acquires a connection slot on an explicitly requested backend (e.g. the {@code send} command). */
    public void acquire(final Backend backend) {
        backend.acquire();
    }

    public void release(final Backend backend) {
        if (backend != null) {
            backend.release();
        }
    }

    /**
     * Feeds a probe or real-connection result into the health state machine. A backend goes down after
     * {@code health-check-failures} consecutive failures and comes back after a single success.
     */
    public void report(final Backend backend, final boolean success, final long latencyMs) {
        final int threshold = config.get().backends().healthFailThreshold();
        synchronized (backend) {
            applyReport(backend, success, latencyMs, threshold);
        }
    }

    private static void applyReport(final Backend backend, final boolean success, final long latencyMs,
                                    final int threshold) {
        if (success) {
            backend.recordProbe(latencyMs);
            backend.setConsecutiveFailures(0);
            if (!backend.healthy()) {
                backend.setHealthy(true);
                TerminalLogger.info("Backend " + describe(backend) + " is UP again");
            }
            return;
        }

        backend.countConnectFailure();
        final int failures = backend.consecutiveFailures() + 1;
        backend.setConsecutiveFailures(failures);
        if (backend.healthy() && failures >= threshold) {
            backend.setHealthy(false);
            TerminalLogger.warn("Backend " + describe(backend) + " is DOWN (" + failures + " consecutive failures)");
        }
    }

    /** Applies the first probe result immediately, without waiting for {@code health-check-failures} rounds. */
    public void markInitial(final Backend backend, final boolean up, final long latencyMs) {
        synchronized (backend) {
            if (up) {
                backend.recordProbe(latencyMs);
                backend.setConsecutiveFailures(0);
                backend.setHealthy(true);
            } else {
                backend.countConnectFailure();
                backend.setConsecutiveFailures(config.get().backends().healthFailThreshold());
                backend.setHealthy(false);
            }
        }
    }

    public static String describe(final Backend backend) {
        return backend.name() + " (" + backend.address() + ")";
    }

    private static Backend leastConnections(final List<Backend> candidates) {
        Backend best = null;
        double bestLoad = Double.MAX_VALUE;
        for (final Backend candidate : candidates) {
            final double load = (double) candidate.activeConnections() / Math.max(1, candidate.weight());
            if (load < bestLoad) {
                best = candidate;
                bestLoad = load;
            }
        }
        return best;
    }

    /** Smooth weighted round-robin (the nginx algorithm): spreads picks evenly instead of in bursts. */
    private Backend weighted(final List<Backend> candidates) {
        synchronized (weightedLock) {
            int total = 0;
            Backend best = null;
            for (final Backend candidate : candidates) {
                final int weight = Math.max(1, candidate.weight());
                candidate.currentWeight += weight;
                total += weight;
                if (best == null || candidate.currentWeight > best.currentWeight) {
                    best = candidate;
                }
            }
            best.currentWeight -= total;
            return best;
        }
    }

    private static String key(final String host, final int port) {
        return host.toLowerCase(Locale.ROOT) + ":" + port;
    }
}

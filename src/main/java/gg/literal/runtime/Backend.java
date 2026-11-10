package gg.literal.runtime;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/** Live state of one upstream server. Instances survive config reloads while their address stays the same. */
public final class Backend {

    private final String host;
    private final int port;
    private volatile String name;
    private volatile int weight;
    private volatile int maxConnections;

    private volatile boolean healthy = true;
    private volatile boolean draining;
    private volatile int consecutiveFailures;
    private volatile long latencyMs = -1L;
    private volatile long lastCheckMillis;

    private final AtomicInteger activeConnections = new AtomicInteger();
    private final LongAdder totalConnections = new LongAdder();
    private final LongAdder connectFailures = new LongAdder();

    /** Smooth weighted round-robin accumulator; only touched while holding the pool's weighted lock. */
    int currentWeight;

    Backend(final String name, final String host, final int port, final int weight, final int maxConnections) {
        this.name = name;
        this.host = host;
        this.port = port;
        this.weight = weight;
        this.maxConnections = maxConnections;
    }

    public String name() {
        return name;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public String address() {
        return host + ":" + port;
    }

    public int weight() {
        return weight;
    }

    public boolean healthy() {
        return healthy;
    }

    /** A draining backend keeps its existing sessions but is skipped for new players. */
    public boolean draining() {
        return draining;
    }

    public void setDraining(final boolean value) {
        this.draining = value;
    }

    /** Configured cap on simultaneous player connections, or 0 for unlimited. */
    public int maxConnections() {
        return maxConnections;
    }

    /** False once the connection cap is reached. */
    public boolean hasCapacity() {
        final int cap = maxConnections;
        return cap <= 0 || activeConnections.get() < cap;
    }

    /** Healthy and not draining: eligible for new players. */
    public boolean available() {
        return healthy && !draining;
    }

    public int activeConnections() {
        return activeConnections.get();
    }

    public long totalConnections() {
        return totalConnections.sum();
    }

    public long connectFailures() {
        return connectFailures.sum();
    }

    /** Duration of the last successful health probe in ms, or -1 if there is none. */
    public long latencyMs() {
        return latencyMs;
    }

    public long lastCheckMillis() {
        return lastCheckMillis;
    }

    void update(final String newName, final int newWeight, final int newMaxConnections) {
        this.name = newName;
        this.weight = newWeight;
        this.maxConnections = newMaxConnections;
    }

    void setHealthy(final boolean value) {
        this.healthy = value;
    }

    int consecutiveFailures() {
        return consecutiveFailures;
    }

    void setConsecutiveFailures(final int value) {
        this.consecutiveFailures = value;
    }

    void recordProbe(final long latency) {
        this.latencyMs = latency;
        this.lastCheckMillis = System.currentTimeMillis();
    }

    void acquire() {
        activeConnections.incrementAndGet();
        totalConnections.increment();
    }

    void release() {
        activeConnections.decrementAndGet();
    }

    void countConnectFailure() {
        connectFailures.increment();
    }
}

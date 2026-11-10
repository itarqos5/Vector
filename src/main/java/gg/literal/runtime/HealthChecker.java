package gg.literal.runtime;

import gg.literal.config.VectorConfig;
import gg.literal.detect.BackendDetector;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically opens a TCP connection to every backend. This is deliberately a plain connect (no
 * Minecraft handshake) so it works regardless of PROXY protocol or forwarding settings on the backend.
 */
public final class HealthChecker {

    private final BackendPool pool;
    private final ConfigHolder config;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService probes = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean running;

    public HealthChecker(final BackendPool pool, final ConfigHolder config, final ScheduledExecutorService scheduler) {
        this.pool = pool;
        this.config = config;
        this.scheduler = scheduler;
    }

    /** Runs one probe round and blocks until it finishes. Used once at boot so routing starts with accurate state. */
    public void checkNow() {
        runRound(true);
    }

    public void start() {
        running = true;
        scheduleNext();
    }

    public void stop() {
        running = false;
        probes.shutdownNow();
    }

    private void scheduleNext() {
        if (!running) {
            return;
        }
        scheduler.schedule(() -> {
            try {
                runRound(false);
            } catch (RuntimeException ignored) {
                // never let one bad round kill the schedule
            } finally {
                scheduleNext();
            }
        }, config.get().backends().healthIntervalMs(), TimeUnit.MILLISECONDS);
    }

    private void runRound(final boolean initial) {
        final VectorConfig cfg = config.get();
        final int timeout = cfg.backends().healthTimeoutMs();
        final boolean statusProbe = cfg.backends().healthMode() == VectorConfig.HealthMode.STATUS;
        final boolean proxyProtocol = cfg.proxyProtocol();
        final List<Future<?>> futures = new ArrayList<>();
        for (final Backend backend : pool.all()) {
            futures.add(probes.submit(() -> probe(backend, timeout, initial, statusProbe, proxyProtocol)));
        }
        for (final Future<?> future : futures) {
            try {
                future.get(timeout + 1000L, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                future.cancel(true);
            }
        }
    }

    private void probe(final Backend backend, final int timeoutMs, final boolean initial,
                       final boolean statusProbe, final boolean proxyProtocol) {
        final long start = System.nanoTime();
        try {
            if (statusProbe) {
                if (BackendDetector.fetchStatusJson(backend.host(), backend.port(), proxyProtocol, timeoutMs, timeoutMs, false) == null) {
                    throw new IOException("no status response");
                }
            } else {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(backend.host(), backend.port()), timeoutMs);
                }
            }
            final long latency = (System.nanoTime() - start) / 1_000_000L;
            if (initial) {
                pool.markInitial(backend, true, latency);
            } else {
                pool.report(backend, true, latency);
            }
        } catch (Exception ex) {
            if (initial) {
                pool.markInitial(backend, false, -1L);
            } else {
                pool.report(backend, false, -1L);
            }
        }
    }
}

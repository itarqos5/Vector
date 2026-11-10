package gg.literal.runtime;

import gg.literal.config.Json;
import gg.literal.config.VectorConfig;
import gg.literal.detect.BackendDetector;
import gg.literal.log.TerminalLogger;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Answers Server List Pings without touching a backend. A background task polls every healthy backend,
 * merges the results (optionally summing player counts) and keeps the finished response packet ready,
 * so a flood of pings costs one array copy each. While every backend is down an "offline" MOTD is served.
 */
public final class StatusService {

    public enum Decision { MAINTENANCE, OFFLINE, CACHED, PASSTHROUGH }

    private record Snapshot(Map<String, Object> json, long takenAt) {
    }

    private final SyntheticStatus offline;
    private final SyntheticStatus maintenance;
    private final BackendPool pool;
    private final ConfigHolder config;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService probes = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<Backend, Snapshot> snapshots = new ConcurrentHashMap<>();
    private volatile byte[] composedPacket;
    private volatile boolean running;

    public StatusService(final BackendPool pool, final ConfigHolder config, final ScheduledExecutorService scheduler) {
        this.pool = pool;
        this.config = config;
        this.scheduler = scheduler;
        this.maintenance = new SyntheticStatus("Maintenance", () -> config.get().policy().maintenanceMotd());
        this.offline = new SyntheticStatus("Offline", () -> config.get().status().offlineMotd());
    }

    public void start() {
        running = true;
        refresh();
        scheduleNext();
    }

    public void stop() {
        running = false;
        probes.shutdownNow();
    }

    /** What to do with a status request arriving right now. */
    public Decision decide() {
        if (config.get().policy().maintenance()) {
            return Decision.MAINTENANCE;
        }
        final VectorConfig.Status settings = config.get().status();
        if (pool.healthyCount() == 0 && !settings.offlineMotd().isEmpty()) {
            return Decision.OFFLINE;
        }
        if (settings.cacheEnabled() && composedPacket != null) {
            return Decision.CACHED;
        }
        return Decision.PASSTHROUGH;
    }

    /** Builds the Status Response packet for {@code decision}; the caller owns the returned buffer. */
    public ByteBuf response(final Decision decision, final int clientProtocol, final ByteBufAllocator alloc) {
        if (decision == Decision.CACHED) {
            final byte[] packet = composedPacket;
            if (packet != null) {
                Metrics.INSTANCE.statusFromCache.increment();
                return alloc.buffer(packet.length).writeBytes(packet);
            }
        }
        Metrics.INSTANCE.statusOffline.increment();
        final SyntheticStatus synthetic = decision == Decision.MAINTENANCE ? maintenance : offline;
        return alloc.buffer().writeBytes(synthetic.packet(clientProtocol));
    }

    // ------------------------------------------------------------------ refresh

    private void scheduleNext() {
        if (!running) {
            return;
        }
        scheduler.schedule(() -> {
            try {
                refresh();
            } catch (RuntimeException ex) {
                TerminalLogger.warn("Status refresh failed: " + ex.getMessage());
            } finally {
                scheduleNext();
            }
        }, config.get().status().cacheTtlMs(), TimeUnit.MILLISECONDS);
    }

    private void refresh() {
        final VectorConfig cfg = config.get();
        if (!cfg.status().cacheEnabled()) {
            snapshots.clear();
            composedPacket = null;
            return;
        }

        final int timeout = cfg.backends().healthTimeoutMs();
        final boolean proxyProtocol = cfg.proxyProtocol();
        final Map<Backend, Future<String>> pending = new LinkedHashMap<>();
        for (final Backend backend : pool.all()) {
            if (backend.healthy()) {
                pending.put(backend, probes.submit(
                    () -> BackendDetector.fetchStatusJson(backend.host(), backend.port(), proxyProtocol, timeout, timeout, false)));
            }
        }

        final long now = System.currentTimeMillis();
        for (final Map.Entry<Backend, Future<String>> entry : pending.entrySet()) {
            String json = null;
            try {
                json = entry.getValue().get(timeout * 2L + 1000L, TimeUnit.MILLISECONDS);
            } catch (Exception ex) {
                entry.getValue().cancel(true);
            }
            if (json == null) {
                snapshots.remove(entry.getKey());
                continue;
            }
            try {
                snapshots.put(entry.getKey(), new Snapshot(Json.parseObject(json), now));
            } catch (IllegalArgumentException ex) {
                snapshots.remove(entry.getKey());
            }
        }
        snapshots.keySet().removeIf(backend -> !pool.all().contains(backend));
        compose(cfg, now);
    }

    @SuppressWarnings("unchecked")
    private void compose(final VectorConfig cfg, final long now) {
        final long maxAge = Math.max(15_000L, cfg.status().cacheTtlMs() * 3L);
        final List<Map<String, Object>> live = new ArrayList<>();
        for (final Backend backend : pool.all()) {
            final Snapshot snapshot = snapshots.get(backend);
            if (backend.healthy() && snapshot != null && now - snapshot.takenAt() <= maxAge) {
                live.add(snapshot.json());
            }
        }
        if (live.isEmpty()) {
            composedPacket = null;
            return;
        }

        final Map<String, Object> merged = new LinkedHashMap<>(live.get(0));
        if (cfg.status().sumPlayers() && live.size() > 1) {
            long online = 0;
            long max = 0;
            for (final Map<String, Object> json : live) {
                if (json.get("players") instanceof Map<?, ?> players) {
                    online += number(players.get("online"));
                    max += number(players.get("max"));
                }
            }
            final Map<String, Object> players = merged.get("players") instanceof Map<?, ?> original
                ? new LinkedHashMap<>((Map<String, Object>) original)
                : new LinkedHashMap<>();
            players.put("online", online);
            players.put("max", max);
            merged.put("players", players);
        }
        composedPacket = McProtocol.stringPacketBytes(McProtocol.PACKET_STATUS_RESPONSE, Json.write(merged));
    }

    private static long number(final Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }
}

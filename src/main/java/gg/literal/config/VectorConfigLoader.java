package gg.literal.config;

import gg.literal.bootstrap.BootstrapException;
import gg.literal.config.VectorConfig.Access;
import gg.literal.config.VectorConfig.BackendTarget;
import gg.literal.config.VectorConfig.Backends;
import gg.literal.config.VectorConfig.Dashboard;
import gg.literal.config.VectorConfig.BalanceMode;
import gg.literal.config.VectorConfig.Limits;
import gg.literal.config.VectorConfig.Metrics;
import gg.literal.config.VectorConfig.Policy;
import gg.literal.config.VectorConfig.Status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class VectorConfigLoader {

    private VectorConfigLoader() {
    }

    /** Loads {@code path}, writing a default file first if it does not exist yet. */
    public static VectorConfig loadOrCreate(final Path path) throws BootstrapException {
        if (Files.notExists(path)) {
            final VectorConfig defaults = VectorConfig.defaults();
            writeDefaults(path, defaults);
            return defaults;
        }
        return load(path);
    }

    /** Loads and validates {@code path}. Missing keys fall back to defaults. */
    public static VectorConfig load(final Path path) throws BootstrapException {
        final String content;
        try {
            content = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new BootstrapException("Unable to read " + path.getFileName(), ex);
        }

        final Map<String, Object> root;
        try {
            root = Json.parseObject(content);
        } catch (IllegalArgumentException ex) {
            throw new BootstrapException(path.getFileName() + " is not valid JSON: " + ex.getMessage(), ex);
        }

        try {
            return fromMap(root);
        } catch (IllegalArgumentException ex) {
            throw new BootstrapException("Invalid " + path.getFileName() + ": " + ex.getMessage(), ex);
        }
    }

    private static VectorConfig fromMap(final Map<String, Object> root) {
        final VectorConfig d = VectorConfig.defaults();

        final String bindHost = string(root, "bind-host", d.bindHost());
        final int bindPort = intValue(root, "bind-port", d.bindPort(), 1, 65535);
        final int bossThreads = intValue(root, "boss-threads", d.bossThreads(), 1, 1024);
        final int workerThreads = intValue(root, "worker-threads", d.workerThreads(), 1, 1024);
        final long maxBytes = longValue(root, "max-bytes-per-second", d.maxBytesPerSecond(), 1, Long.MAX_VALUE);
        final long maxPackets = longValue(root, "max-packets-per-second", d.maxPacketsPerSecond(), 1, Long.MAX_VALUE);
        final int connectTimeout = intValue(root, "connect-timeout-ms", d.connectTimeoutMs(), 100, 600_000);
        final boolean proxyProtocol = bool(root, "proxy-protocol", d.proxyProtocol());

        final Backends db = d.backends();
        final BalanceMode mode;
        if (root.containsKey("balance-mode")) {
            final String raw = string(root, "balance-mode", db.mode().id());
            mode = BalanceMode.parse(raw);
            if (mode == null) {
                throw new IllegalArgumentException("balance-mode \"" + raw
                    + "\" is unknown (use round-robin, least-connections, weighted or ip-hash)");
            }
        } else {
            mode = db.mode();
        }
        final Backends backends = new Backends(
            readBackends(root, d),
            mode,
            intValue(root, "health-check-interval-ms", db.healthIntervalMs(), 250, 3_600_000),
            intValue(root, "health-check-timeout-ms", db.healthTimeoutMs(), 100, 600_000),
            intValue(root, "health-check-failures", db.healthFailThreshold(), 1, 1000),
            intValue(root, "max-connect-attempts", db.maxConnectAttempts(), 1, 32)
        );

        final Limits dl = d.limits();
        final Limits limits = new Limits(
            intValue(root, "max-connections-per-ip", dl.maxConnectionsPerIp(), 0, Integer.MAX_VALUE),
            intValue(root, "max-connection-rate-per-ip", dl.maxConnectionRatePerIp(), 0, Integer.MAX_VALUE),
            intValue(root, "connection-rate-window-ms", dl.connectionRateWindowMs(), 100, 3_600_000)
        );

        final Status ds = d.status();
        final Status status = new Status(
            bool(root, "status-cache-enabled", ds.cacheEnabled()),
            intValue(root, "status-cache-ttl-ms", ds.cacheTtlMs(), 250, 3_600_000),
            string(root, "offline-motd", ds.offlineMotd()),
            bool(root, "status-sum-players", ds.sumPlayers())
        );

        final Access da = d.access();
        final Access access = new Access(
            bool(root, "whitelist-enabled", da.whitelistEnabled()),
            string(root, "whitelist-message", da.whitelistMessage())
        );

        final Metrics dm = d.metrics();
        final Metrics metrics = new Metrics(
            bool(root, "metrics-enabled", dm.enabled()),
            string(root, "metrics-host", dm.host()),
            intValue(root, "metrics-port", dm.port(), 1, 65535),
            string(root, "metrics-token", dm.token())
        );

        final Dashboard dd = d.dashboard();
        final Dashboard dashboard = new Dashboard(
            bool(root, "dashboard-enabled", dd.enabled()),
            string(root, "dashboard-host", dd.host()),
            intValue(root, "dashboard-port", dd.port(), 1, 65535),
            string(root, "dashboard-password", dd.password())
        );
        if (dashboard.enabled() && dashboard.port() == bindPort) {
            throw new IllegalArgumentException("dashboard-port must differ from bind-port (both are " + bindPort + ")");
        }
        if (dashboard.enabled() && metrics.enabled() && dashboard.port() == metrics.port()
            && dashboard.host().equals(metrics.host())) {
            throw new IllegalArgumentException("dashboard-port must differ from metrics-port (both are " + metrics.port() + ")");
        }

        final Policy dp = d.policy();
        final Policy policy = new Policy(
            bool(root, "log-chat", dp.logChat()),
            intValue(root, "idle-timeout-seconds", dp.idleTimeoutSeconds(), 0, 86_400)
        );

        return new VectorConfig(
            bindHost, bindPort, bossThreads, workerThreads, maxBytes, maxPackets,
            connectTimeout, proxyProtocol, backends, limits, status, access, metrics, dashboard, policy
        );
    }

    /**
     * Reads the {@code backends} array. When it is absent the legacy single-backend keys
     * ({@code backend-host} / {@code backend-port}) are used so older config files keep working.
     */
    private static List<BackendTarget> readBackends(final Map<String, Object> root, final VectorConfig d) {
        final Object raw = root.get("backends");
        final BackendTarget fallback = d.backends().targets().get(0);

        if (raw == null) {
            return List.of(new BackendTarget(
                "main",
                string(root, "backend-host", fallback.host()),
                intValue(root, "backend-port", fallback.port(), 1, 65535),
                1
            ));
        }
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("\"backends\" must be a non-empty array");
        }

        final List<BackendTarget> targets = new ArrayList<>();
        final Set<String> names = new HashSet<>();
        final Set<String> addresses = new HashSet<>();
        int index = 1;
        for (final Object element : list) {
            if (!(element instanceof Map<?, ?> rawEntry)) {
                throw new IllegalArgumentException("backends[" + (index - 1) + "] must be an object");
            }
            @SuppressWarnings("unchecked")
            final Map<String, Object> entry = (Map<String, Object>) rawEntry;
            final String host = string(entry, "host", "");
            if (host.isBlank()) {
                throw new IllegalArgumentException("backends[" + (index - 1) + "] is missing \"host\"");
            }
            final int port = intValue(entry, "port", 25565, 1, 65535);
            final int weight = intValue(entry, "weight", 1, 1, 1_000_000);
            final String name = string(entry, "name", "backend-" + index).trim();
            if (name.isEmpty() || name.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("backend names must be non-empty and contain no spaces: \"" + name + "\"");
            }
            if (!names.add(name.toLowerCase())) {
                throw new IllegalArgumentException("duplicate backend name \"" + name + "\"");
            }
            final BackendTarget target = new BackendTarget(name, host.trim(), port, weight);
            if (!addresses.add(target.address().toLowerCase())) {
                throw new IllegalArgumentException("duplicate backend address " + target.address());
            }
            targets.add(target);
            index++;
        }
        return List.copyOf(targets);
    }

    // ------------------------------------------------------------------ typed accessors

    private static String string(final Map<String, Object> map, final String key, final String fallback) {
        final Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException("\"" + key + "\" must be a string");
        }
        return s;
    }

    private static boolean bool(final Map<String, Object> map, final String key, final boolean fallback) {
        final Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Boolean b)) {
            throw new IllegalArgumentException("\"" + key + "\" must be true or false");
        }
        return b;
    }

    private static long longValue(final Map<String, Object> map, final String key, final long fallback,
                                  final long min, final long max) {
        final Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Long l)) {
            throw new IllegalArgumentException("\"" + key + "\" must be a whole number");
        }
        if (l < min || l > max) {
            throw new IllegalArgumentException("\"" + key + "\" must be between " + min + " and " + max + " (got " + l + ")");
        }
        return l;
    }

    private static int intValue(final Map<String, Object> map, final String key, final int fallback,
                                final int min, final int max) {
        return (int) longValue(map, key, fallback, min, max);
    }

    // ------------------------------------------------------------------ defaults file

    private static void writeDefaults(final Path path, final VectorConfig d) throws BootstrapException {
        final StringBuilder sb = new StringBuilder("{\n");
        line(sb, "bind-host", Json.quote(d.bindHost()));
        line(sb, "bind-port", d.bindPort());
        sb.append("  \"backends\": [\n");
        final List<BackendTarget> targets = d.backends().targets();
        for (int i = 0; i < targets.size(); i++) {
            final BackendTarget t = targets.get(i);
            sb.append("    { \"name\": ").append(Json.quote(t.name()))
                .append(", \"host\": ").append(Json.quote(t.host()))
                .append(", \"port\": ").append(t.port())
                .append(", \"weight\": ").append(t.weight()).append(" }")
                .append(i + 1 < targets.size() ? ",\n" : "\n");
        }
        sb.append("  ],\n");
        line(sb, "balance-mode", Json.quote(d.backends().mode().id()));
        line(sb, "health-check-interval-ms", d.backends().healthIntervalMs());
        line(sb, "health-check-timeout-ms", d.backends().healthTimeoutMs());
        line(sb, "health-check-failures", d.backends().healthFailThreshold());
        line(sb, "max-connect-attempts", d.backends().maxConnectAttempts());
        line(sb, "boss-threads", d.bossThreads());
        line(sb, "worker-threads", d.workerThreads());
        line(sb, "max-bytes-per-second", d.maxBytesPerSecond());
        line(sb, "max-packets-per-second", d.maxPacketsPerSecond());
        line(sb, "connect-timeout-ms", d.connectTimeoutMs());
        line(sb, "proxy-protocol", d.proxyProtocol());
        line(sb, "max-connections-per-ip", d.limits().maxConnectionsPerIp());
        line(sb, "max-connection-rate-per-ip", d.limits().maxConnectionRatePerIp());
        line(sb, "connection-rate-window-ms", d.limits().connectionRateWindowMs());
        line(sb, "status-cache-enabled", d.status().cacheEnabled());
        line(sb, "status-cache-ttl-ms", d.status().cacheTtlMs());
        line(sb, "status-sum-players", d.status().sumPlayers());
        line(sb, "offline-motd", Json.quote(d.status().offlineMotd()));
        line(sb, "whitelist-enabled", d.access().whitelistEnabled());
        line(sb, "whitelist-message", Json.quote(d.access().whitelistMessage()));
        line(sb, "metrics-enabled", d.metrics().enabled());
        line(sb, "metrics-host", Json.quote(d.metrics().host()));
        line(sb, "metrics-port", d.metrics().port());
        line(sb, "metrics-token", Json.quote(d.metrics().token()));
        line(sb, "log-chat", d.policy().logChat());
        line(sb, "idle-timeout-seconds", d.policy().idleTimeoutSeconds());
        line(sb, "dashboard-enabled", d.dashboard().enabled());
        line(sb, "dashboard-host", Json.quote(d.dashboard().host()));
        line(sb, "dashboard-port", d.dashboard().port());
        sb.append("  \"dashboard-password\": ").append(Json.quote(d.dashboard().password())).append('\n');
        sb.append("}\n");
        try {
            Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new BootstrapException("Unable to create config.json", ex);
        }
    }

    private static void line(final StringBuilder sb, final String key, final Object rawValue) {
        sb.append("  \"").append(key).append("\": ").append(rawValue).append(",\n");
    }
}

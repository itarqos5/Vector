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

        applyEnvironment(root, System.getenv());

        try {
            return fromMap(root);
        } catch (IllegalArgumentException ex) {
            throw new BootstrapException("Invalid " + path.getFileName() + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * Lets environment variables override the file, which is how containers are usually configured:
     * {@code VECTOR_BIND_PORT=25570} sets {@code bind-port}, {@code VECTOR_DASHBOARD_HOST=0.0.0.0} sets
     * {@code dashboard-host}. Values are read as JSON when they parse (numbers, booleans, arrays such as
     * {@code VECTOR_BACKENDS='[{"host":"10.0.0.2","port":25577}]'}) and as plain text otherwise.
     */
    static void applyEnvironment(final Map<String, Object> root, final Map<String, String> env) {
        for (final Map.Entry<String, String> entry : env.entrySet()) {
            final String name = entry.getKey();
            if (!name.startsWith("VECTOR_") || name.length() == "VECTOR_".length() || entry.getValue() == null) {
                continue;
            }
            final String key = name.substring("VECTOR_".length()).toLowerCase(java.util.Locale.ROOT).replace('_', '-');
            final String raw = entry.getValue().trim();
            Object value;
            try {
                value = Json.parse(raw);
            } catch (IllegalArgumentException notJson) {
                value = entry.getValue();
            }
            root.put(key, value);
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
        final List<BackendTarget> targets = readBackends(root, d);
        final Backends backends = new Backends(
            targets,
            mode,
            intValue(root, "health-check-interval-ms", db.healthIntervalMs(), 250, 3_600_000),
            intValue(root, "health-check-timeout-ms", db.healthTimeoutMs(), 100, 600_000),
            intValue(root, "health-check-failures", db.healthFailThreshold(), 1, 1000),
            intValue(root, "max-connect-attempts", db.maxConnectAttempts(), 1, 32),
            healthMode(root, db.healthMode()),
            readRoutes(root, targets)
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
            intValue(root, "idle-timeout-seconds", dp.idleTimeoutSeconds(), 0, 86_400),
            intValue(root, "handshake-timeout-ms", dp.handshakeTimeoutMs(), 0, 600_000),
            intValue(root, "max-connections", dp.maxConnections(), 0, Integer.MAX_VALUE),
            bool(root, "maintenance-enabled", dp.maintenance()),
            string(root, "maintenance-message", dp.maintenanceMessage()),
            string(root, "maintenance-motd", dp.maintenanceMotd()),
            string(root, "full-message", dp.fullMessage()),
            intValue(root, "min-protocol", dp.minProtocol(), 0, Integer.MAX_VALUE),
            intValue(root, "max-protocol", dp.maxProtocol(), 0, Integer.MAX_VALUE),
            string(root, "protocol-message", dp.protocolMessage()),
            stringList(root, "allowed-hosts", dp.allowedHosts()),
            intValue(root, "auto-ban-violations", dp.autoBanViolations(), 0, 1_000_000),
            intValue(root, "auto-ban-window-seconds", dp.autoBanWindowSeconds(), 1, 86_400),
            intValue(root, "auto-ban-minutes", dp.autoBanMinutes(), 1, 525_600),
            bool(root, "log-to-file", dp.logToFile()),
            intValue(root, "log-retention-days", dp.logRetentionDays(), 0, 36_500)
        );
        if (policy.maxProtocol() != 0 && policy.minProtocol() > policy.maxProtocol()) {
            throw new IllegalArgumentException("min-protocol (" + policy.minProtocol()
                + ") must not be greater than max-protocol (" + policy.maxProtocol() + ")");
        }

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
            final int maxConnections = intValue(entry, "max-connections", 0, 0, Integer.MAX_VALUE);
            final BackendTarget target = new BackendTarget(name, host.trim(), port, weight, maxConnections);
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

    /** Reads the optional {@code routes} array and checks that every route points at configured backends. */
    private static List<VectorConfig.Route> readRoutes(final Map<String, Object> root, final List<BackendTarget> targets) {
        final Object raw = root.get("routes");
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("\"routes\" must be an array");
        }
        final Set<String> known = new HashSet<>();
        for (final BackendTarget target : targets) {
            known.add(target.name().toLowerCase());
        }

        final List<VectorConfig.Route> routes = new ArrayList<>();
        final Set<String> hosts = new HashSet<>();
        int index = 0;
        for (final Object element : list) {
            if (!(element instanceof Map<?, ?> rawEntry)) {
                throw new IllegalArgumentException("routes[" + index + "] must be an object");
            }
            @SuppressWarnings("unchecked")
            final Map<String, Object> entry = (Map<String, Object>) rawEntry;
            final String host = string(entry, "host", "").trim().toLowerCase();
            if (host.isEmpty()) {
                throw new IllegalArgumentException("routes[" + index + "] is missing \"host\"");
            }
            if (!hosts.add(host)) {
                throw new IllegalArgumentException("duplicate route for host \"" + host + "\"");
            }
            if (!(entry.get("backends") instanceof List<?> names) || names.isEmpty()) {
                throw new IllegalArgumentException("route \"" + host + "\" needs a non-empty \"backends\" array");
            }
            final List<String> backendNames = new ArrayList<>();
            for (final Object name : names) {
                if (!(name instanceof String s) || !known.contains(s.trim().toLowerCase())) {
                    throw new IllegalArgumentException("route \"" + host + "\" references unknown backend " + name);
                }
                backendNames.add(s.trim());
            }
            routes.add(new VectorConfig.Route(host, List.copyOf(backendNames)));
            index++;
        }
        return List.copyOf(routes);
    }

    private static VectorConfig.HealthMode healthMode(final Map<String, Object> root, final VectorConfig.HealthMode fallback) {
        if (!root.containsKey("health-check-mode")) {
            return fallback;
        }
        final String raw = string(root, "health-check-mode", fallback.name());
        final VectorConfig.HealthMode mode = VectorConfig.HealthMode.parse(raw);
        if (mode == null) {
            throw new IllegalArgumentException("health-check-mode \"" + raw + "\" is unknown (use tcp or status)");
        }
        return mode;
    }

    private static List<String> stringList(final Map<String, Object> map, final String key, final List<String> fallback) {
        final Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("\"" + key + "\" must be an array of strings");
        }
        final List<String> out = new ArrayList<>();
        for (final Object element : list) {
            if (!(element instanceof String s) || s.isBlank()) {
                throw new IllegalArgumentException("\"" + key + "\" must only contain non-empty strings");
            }
            out.add(s.trim());
        }
        return List.copyOf(out);
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
        line(sb, "health-check-mode", Json.quote(d.backends().healthMode().name().toLowerCase()));
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
        line(sb, "handshake-timeout-ms", d.policy().handshakeTimeoutMs());
        line(sb, "max-connections", d.policy().maxConnections());
        line(sb, "maintenance-enabled", d.policy().maintenance());
        line(sb, "maintenance-message", Json.quote(d.policy().maintenanceMessage()));
        line(sb, "maintenance-motd", Json.quote(d.policy().maintenanceMotd()));
        line(sb, "full-message", Json.quote(d.policy().fullMessage()));
        line(sb, "min-protocol", d.policy().minProtocol());
        line(sb, "max-protocol", d.policy().maxProtocol());
        line(sb, "protocol-message", Json.quote(d.policy().protocolMessage()));
        line(sb, "allowed-hosts", Json.write(d.policy().allowedHosts()));
        line(sb, "auto-ban-violations", d.policy().autoBanViolations());
        line(sb, "auto-ban-window-seconds", d.policy().autoBanWindowSeconds());
        line(sb, "auto-ban-minutes", d.policy().autoBanMinutes());
        line(sb, "log-to-file", d.policy().logToFile());
        line(sb, "log-retention-days", d.policy().logRetentionDays());
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

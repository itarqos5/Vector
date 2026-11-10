package gg.literal.config;

import java.util.List;
import java.util.Locale;

/**
 * Immutable snapshot of {@code config.json}. Lives in the launcher class loader so it can be
 * shared with the child-first runtime class loader.
 */
public record VectorConfig(
    String bindHost,
    int bindPort,
    int bossThreads,
    int workerThreads,
    long maxBytesPerSecond,
    long maxPacketsPerSecond,
    int connectTimeoutMs,
    boolean proxyProtocol,
    Backends backends,
    Limits limits,
    Status status,
    Access access,
    Metrics metrics,
    Dashboard dashboard,
    Policy policy
) {

    /**
     * A single upstream server.
     *
     * @param maxConnections cap on simultaneous player connections to this backend (0 = unlimited); once it is
     *                       reached new players go to the other backends
     */
    public record BackendTarget(String name, String host, int port, int weight, int maxConnections) {

        public BackendTarget(final String name, final String host, final int port, final int weight) {
            this(name, host, port, weight, 0);
        }
        public String address() {
            return host + ":" + port;
        }
    }

    public enum BalanceMode {
        ROUND_ROBIN("round-robin"),
        LEAST_CONNECTIONS("least-connections"),
        WEIGHTED("weighted"),
        IP_HASH("ip-hash"),
        RANDOM("random"),
        LEAST_LATENCY("least-latency");

        private final String id;

        BalanceMode(final String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        /** Returns the matching mode, or {@code null} if {@code value} is not recognised. */
        public static BalanceMode parse(final String value) {
            if (value == null) {
                return null;
            }
            final String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
            for (final BalanceMode mode : values()) {
                if (mode.id.equals(normalized)) {
                    return mode;
                }
            }
            return null;
        }
    }

    /**
     * Sends players who connected with a given host name to a subset of the backends.
     *
     * @param host     exact name ("lobby.example.com"), wildcard ("*.example.com") or "*" for everything else
     * @param backends names of the backends that may serve this host
     */
    public record Route(String host, List<String> backends) {
    }

    /** How a backend is probed. */
    public enum HealthMode {
        /** A plain TCP connect: cheap and independent of PROXY protocol or forwarding settings. */
        TCP,
        /** A real server list ping: also proves the Minecraft server (not just the port) is answering. */
        STATUS;

        public static HealthMode parse(final String value) {
            if (value == null) {
                return null;
            }
            for (final HealthMode mode : values()) {
                if (mode.name().equalsIgnoreCase(value.trim())) {
                    return mode;
                }
            }
            return null;
        }
    }

    /**
     * Backend pool settings.
     *
     * @param healthIntervalMs     delay between active TCP health probes
     * @param healthTimeoutMs      connect timeout of a health probe
     * @param healthFailThreshold  consecutive failures before a backend is marked down
     * @param maxConnectAttempts   how many distinct backends a player connection may try before giving up
     * @param healthMode           how backends are probed
     * @param routes               optional host name routing table (empty = every player may use every backend)
     */
    public record Backends(
        List<BackendTarget> targets,
        BalanceMode mode,
        int healthIntervalMs,
        int healthTimeoutMs,
        int healthFailThreshold,
        int maxConnectAttempts,
        HealthMode healthMode,
        List<Route> routes
    ) {
    }

    /** Per-IP connection limits. A value of {@code 0} disables the respective limit. */
    public record Limits(int maxConnectionsPerIp, int maxConnectionRatePerIp, int connectionRateWindowMs) {
    }

    /**
     * Server list ping handling.
     *
     * @param cacheEnabled whether pings are answered from a periodically refreshed cache
     * @param cacheTtlMs   refresh interval of the cache
     * @param offlineMotd  MOTD shown while every backend is down; empty disables the offline response
     * @param sumPlayers   report the summed player counts of all healthy backends
     */
    public record Status(boolean cacheEnabled, int cacheTtlMs, String offlineMotd, boolean sumPlayers) {
    }

    /** Login gating. */
    public record Access(boolean whitelistEnabled, String whitelistMessage) {
    }

    /** Metrics HTTP endpoint. */
    public record Metrics(boolean enabled, String host, int port, String token) {
    }

    /**
     * Web dashboard.
     *
     * @param password login password; when empty the dashboard is open on a loopback address, and a random
     *                 password is generated at startup if it is bound anywhere else
     */
    public record Dashboard(boolean enabled, String host, int port, String password) {
    }

    /**
     * Connection policy knobs.
     *
     * @param logChat whether chat and commands are parsed and logged; turning it off lets the protocol sniffer
     *                stop inspecting packets once a player is in game, which saves CPU on busy offline-mode backends
     * @param idleTimeoutSeconds close a connection that has sent nothing for this long (0 disables); real
     *                           clients send keep-alives every few seconds, so silence means a dead peer
     * @param handshakeTimeoutMs close a connection that sends no bytes at all within this time (0 disables),
     *                           which defeats sockets opened only to tie up resources
     * @param maxConnections global cap on open client connections (0 = unlimited)
     * @param maintenance whether maintenance mode is on: logins are refused (whitelisted names and IPs may still
     *                    join)
     * @param maintenanceMessage disconnect text shown to refused players
     * @param maintenanceMotd MOTD shown in the server list while maintenance mode is on
     * @param fullMessage disconnect text when every available backend is at its max-connections cap
     */
    public record Policy(
        boolean logChat,
        int idleTimeoutSeconds,
        int handshakeTimeoutMs,
        int maxConnections,
        boolean maintenance,
        String maintenanceMessage,
        String maintenanceMotd,
        String fullMessage
    ) {
    }

    public static VectorConfig defaults() {
        final int available = Runtime.getRuntime().availableProcessors();
        final int workerDefault = Math.max(1, available - 1);
        return new VectorConfig(
            "0.0.0.0",
            envPortOrDefault(25565),
            1,
            workerDefault,
            2_097_152L,
            4000L,
            3000,
            false,
            new Backends(
                List.of(new BackendTarget("main", "127.0.0.1", 25577, 1)),
                BalanceMode.ROUND_ROBIN,
                5000,
                2000,
                2,
                3,
                HealthMode.TCP,
                List.of()
            ),
            new Limits(20, 20, 10_000),
            new Status(true, 5000, "§cServer is currently offline", true),
            new Access(false, "§cYou are not whitelisted on this server."),
            new Metrics(false, "127.0.0.1", 9100, ""),
            new Dashboard(true, "127.0.0.1", 3000, ""),
            new Policy(true, 120, 5000, 0, false, "\u00a7eThe server is under maintenance. Please try again later.", "\u00a76Under maintenance",
                "\u00a7cAll servers are full right now. Please try again shortly.")
        );
    }

    private static int envPortOrDefault(final int fallback) {
        final String value = System.getenv("SERVER_PORT");
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}

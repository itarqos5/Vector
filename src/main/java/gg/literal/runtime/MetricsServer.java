package gg.literal.runtime;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import gg.literal.config.Json;
import gg.literal.config.VectorConfig;
import gg.literal.log.TerminalLogger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Tiny HTTP endpoint (JDK built-in server) exposing {@code /metrics} (Prometheus text), {@code /stats} (JSON)
 * and {@code /health} (200 while at least one backend is up, 503 otherwise).
 */
public final class MetricsServer {

    private final VectorContext context;
    private HttpServer server;

    public MetricsServer(final VectorContext context) {
        this.context = context;
    }

    public void start() {
        final VectorConfig.Metrics settings = context.config.get().metrics();
        try {
            server = HttpServer.create(new InetSocketAddress(settings.host(), settings.port()), 16);
        } catch (IOException ex) {
            TerminalLogger.error("Metrics endpoint could not bind " + settings.host() + ":" + settings.port()
                + " (" + ex.getMessage() + ")");
            return;
        }
        server.createContext("/metrics", exchange -> respond(exchange, "text/plain; version=0.0.4; charset=utf-8", prometheus(), 200, true));
        server.createContext("/stats", exchange -> respond(exchange, "application/json; charset=utf-8", Json.write(stats()), 200, true));
        server.createContext("/health", exchange -> {
            final boolean up = context.pool.healthyCount() > 0;
            respond(exchange, "text/plain; charset=utf-8", up ? "ok\n" : "no healthy backend\n", up ? 200 : 503, false);
        });
        server.setExecutor(Executors.newFixedThreadPool(2, r -> {
            final Thread thread = new Thread(r, "vector-metrics");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
        TerminalLogger.info("Metrics endpoint listening on http://" + settings.host() + ":" + settings.port()
            + "/metrics" + (settings.token().isEmpty() ? "" : " (bearer token required)"));
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void respond(final HttpExchange exchange, final String type, final String body, final int status,
                         final boolean authenticated) throws IOException {
        try (exchange) {
            if (authenticated && !authorized(exchange)) {
                exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer");
                exchange.sendResponseHeaders(401, -1);
                return;
            }
            final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", type);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private boolean authorized(final HttpExchange exchange) {
        final String token = context.config.get().metrics().token();
        if (token.isEmpty()) {
            return true;
        }
        final String header = exchange.getRequestHeaders().getFirst("Authorization");
        final String expected = "Bearer " + token;
        return header != null && MessageDigest.isEqual(
            header.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ output

    private String prometheus() {
        final Metrics m = Metrics.INSTANCE;
        final StringBuilder sb = new StringBuilder(2048);
        gauge(sb, "vector_uptime_seconds", "Seconds since Vector started", (System.currentTimeMillis() - m.startedAtMillis) / 1000);
        gauge(sb, "vector_connections_active", "Client connections currently open", context.registry.size());
        counter(sb, "vector_connections_total", "Client connections accepted", m.connectionsTotal.sum());
        counter(sb, "vector_bytes_from_clients_total", "Bytes relayed client -> backend", m.bytesFromClients.sum());
        counter(sb, "vector_bytes_to_clients_total", "Bytes relayed backend -> client", m.bytesToClients.sum());

        sb.append("# HELP vector_blocked_total Connections or sessions refused, by reason\n# TYPE vector_blocked_total counter\n");
        blocked(sb, "ban", m.blockedBan.sum());
        blocked(sb, "connection_limit", m.blockedConnectionLimit.sum());
        blocked(sb, "connection_rate", m.blockedConnectionRate.sum());
        blocked(sb, "packet_limit", m.blockedPacketLimit.sum());
        blocked(sb, "whitelist", m.blockedWhitelist.sum());

        sb.append("# HELP vector_status_responses_total Server list pings answered, by source\n# TYPE vector_status_responses_total counter\n");
        status(sb, "cache", m.statusFromCache.sum());
        status(sb, "offline", m.statusOffline.sum());
        status(sb, "backend", m.statusFromBackend.sum());
        counter(sb, "vector_timeouts_total", "Connections closed for being idle or slow to handshake", m.timeouts.sum());
        counter(sb, "vector_no_backend_total", "Player connections dropped because no backend was available", m.noBackendAvailable.sum());

        sb.append("# HELP vector_backend_up 1 if the backend passes health checks\n# TYPE vector_backend_up gauge\n");
        for (final Backend b : context.pool.all()) {
            sb.append("vector_backend_up{backend=\"").append(label(b.name())).append("\"} ").append(b.healthy() ? 1 : 0).append('\n');
        }
        sb.append("# HELP vector_backend_connections_active Open player connections per backend\n# TYPE vector_backend_connections_active gauge\n");
        for (final Backend b : context.pool.all()) {
            sb.append("vector_backend_connections_active{backend=\"").append(label(b.name())).append("\"} ").append(b.activeConnections()).append('\n');
        }
        sb.append("# HELP vector_backend_connections_total Player connections routed per backend\n# TYPE vector_backend_connections_total counter\n");
        for (final Backend b : context.pool.all()) {
            sb.append("vector_backend_connections_total{backend=\"").append(label(b.name())).append("\"} ").append(b.totalConnections()).append('\n');
        }
        sb.append("# HELP vector_backend_connect_failures_total Failed connect attempts per backend\n# TYPE vector_backend_connect_failures_total counter\n");
        for (final Backend b : context.pool.all()) {
            sb.append("vector_backend_connect_failures_total{backend=\"").append(label(b.name())).append("\"} ").append(b.connectFailures()).append('\n');
        }
        sb.append("# HELP vector_backend_latency_ms TCP connect time of the last successful health probe\n# TYPE vector_backend_latency_ms gauge\n");
        for (final Backend b : context.pool.all()) {
            if (b.latencyMs() >= 0) {
                sb.append("vector_backend_latency_ms{backend=\"").append(label(b.name())).append("\"} ").append(b.latencyMs()).append('\n');
            }
        }
        return sb.toString();
    }

    private Map<String, Object> stats() {
        final Metrics m = Metrics.INSTANCE;
        final Map<String, Object> root = new LinkedHashMap<>();
        root.put("uptimeSeconds", (System.currentTimeMillis() - m.startedAtMillis) / 1000);
        root.put("connectionsActive", context.registry.size());
        root.put("connectionsTotal", m.connectionsTotal.sum());
        root.put("bytesFromClients", m.bytesFromClients.sum());
        root.put("bytesToClients", m.bytesToClients.sum());

        final Map<String, Object> blocked = new LinkedHashMap<>();
        blocked.put("ban", m.blockedBan.sum());
        blocked.put("connectionLimit", m.blockedConnectionLimit.sum());
        blocked.put("connectionRate", m.blockedConnectionRate.sum());
        blocked.put("packetLimit", m.blockedPacketLimit.sum());
        blocked.put("whitelist", m.blockedWhitelist.sum());
        root.put("blocked", blocked);

        final Map<String, Object> status = new LinkedHashMap<>();
        status.put("cache", m.statusFromCache.sum());
        status.put("offline", m.statusOffline.sum());
        status.put("backend", m.statusFromBackend.sum());
        root.put("statusResponses", status);

        root.put("balanceMode", context.pool.mode().id());
        final java.util.List<Object> backends = new java.util.ArrayList<>();
        for (final Backend b : context.pool.all()) {
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", b.name());
            entry.put("address", b.address());
            entry.put("weight", b.weight());
            entry.put("healthy", b.healthy());
            entry.put("activeConnections", b.activeConnections());
            entry.put("totalConnections", b.totalConnections());
            entry.put("connectFailures", b.connectFailures());
            entry.put("latencyMs", b.latencyMs());
            backends.add(entry);
        }
        root.put("backends", backends);
        return root;
    }

    private static void gauge(final StringBuilder sb, final String name, final String help, final long value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append("\n# TYPE ").append(name).append(" gauge\n")
            .append(name).append(' ').append(value).append('\n');
    }

    private static void counter(final StringBuilder sb, final String name, final String help, final long value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append("\n# TYPE ").append(name).append(" counter\n")
            .append(name).append(' ').append(value).append('\n');
    }

    private static void blocked(final StringBuilder sb, final String reason, final long value) {
        sb.append("vector_blocked_total{reason=\"").append(reason).append("\"} ").append(value).append('\n');
    }

    private static void status(final StringBuilder sb, final String source, final long value) {
        sb.append("vector_status_responses_total{source=\"").append(source).append("\"} ").append(value).append('\n');
    }

    private static String label(final String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}

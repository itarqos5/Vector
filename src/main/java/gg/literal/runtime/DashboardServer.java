package gg.literal.runtime;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import gg.literal.bootstrap.BootstrapException;
import gg.literal.config.Json;
import gg.literal.config.VectorConfig;
import gg.literal.log.TerminalLogger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Browser dashboard served by the JDK built-in HTTP server: a static single page plus a small JSON API
 * (state snapshot, config view, management actions). No external dependencies.
 *
 * <p>Security model: with a password every API call needs a session cookie (or a Bearer password); logins
 * are throttled per IP. Mutating calls additionally require a custom header and a same-origin
 * {@code Origin}, so other websites cannot drive it from a visitor's browser. Without a password the
 * dashboard only runs on a loopback address and rejects foreign {@code Host} headers (DNS rebinding).
 */
public final class DashboardServer {

    private static final int MAX_BODY_BYTES = 16 * 1024;
    private static final int MAX_CONNECTIONS_LISTED = 500;
    private static final long SESSION_TTL_MS = 12L * 3_600_000L;
    private static final int MAX_LOGIN_FAILURES = 5;
    private static final long LOGIN_LOCK_MS = 60_000L;
    private static final String COOKIE = "vector_session";
    private static final String CSRF_HEADER = "X-Requested-With";
    private static final String CSRF_VALUE = "vector-dashboard";

    private record Asset(byte[] bytes, String contentType) {
    }

    private record Attempts(int failures, long lockedUntil) {
    }

    private record Result(boolean ok, String message) {
    }

    private final VectorContext ctx;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Asset> assets = new HashMap<>();
    private final ConcurrentHashMap<String, Long> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Attempts> attempts = new ConcurrentHashMap<>();

    private HttpServer server;
    private boolean authRequired;
    private byte[] password = new byte[0];

    public DashboardServer(final VectorContext ctx) {
        this.ctx = ctx;
    }

    public void start() {
        final VectorConfig.Dashboard settings = ctx.config.get().dashboard();
        try {
            loadAssets();
        } catch (IOException ex) {
            TerminalLogger.error("Dashboard assets could not be loaded: " + ex.getMessage());
            return;
        }

        final boolean loopback = isLoopbackHost(settings.host());
        String effectivePassword = settings.password();
        boolean generated = false;
        if (effectivePassword.isEmpty() && !loopback) {
            effectivePassword = randomPassword();
            generated = true;
        }
        authRequired = !effectivePassword.isEmpty();
        password = effectivePassword.getBytes(StandardCharsets.UTF_8);

        try {
            server = HttpServer.create(new InetSocketAddress(settings.host(), settings.port()), 32);
        } catch (IOException ex) {
            TerminalLogger.error("Dashboard could not bind " + settings.host() + ":" + settings.port()
                + " (" + ex.getMessage() + ")");
            return;
        }
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(4, runnable -> {
            final Thread thread = new Thread(runnable, "vector-dashboard");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();

        final String shownHost = settings.host().equals("0.0.0.0") || settings.host().equals("::") ? "localhost" : settings.host();
        TerminalLogger.info("Dashboard listening on http://" + shownHost + ":" + settings.port()
            + (loopback ? "" : " (reachable from the network)"));
        if (generated) {
            TerminalLogger.warn("dashboard-password is empty but the dashboard is not on a loopback address. "
                + "Generated a password for this run: " + effectivePassword
                + "  (set dashboard-password in config.json to choose your own)");
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------------ routing

    private void handle(final HttpExchange ex) throws IOException {
        try (ex) {
            ex.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
            ex.getResponseHeaders().add("X-Frame-Options", "DENY");
            ex.getResponseHeaders().add("Referrer-Policy", "no-referrer");
            ex.getResponseHeaders().add("Content-Security-Policy",
                "default-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");

            if (!hostAllowed(ex)) {
                sendJson(ex, 403, error("Host header not allowed"));
                return;
            }

            final String path = ex.getRequestURI().getPath();
            final String method = ex.getRequestMethod();

            if (method.equals("GET") && (path.equals("/") || path.equals("/index.html"))) {
                sendAsset(ex, "index.html");
            } else if (method.equals("GET") && (path.equals("/app.js") || path.equals("/style.css"))) {
                sendAsset(ex, path.substring(1));
            } else if (path.equals("/api/session") && method.equals("GET")) {
                final Map<String, Object> body = new LinkedHashMap<>();
                body.put("authRequired", authRequired);
                body.put("authenticated", !authRequired || isAuthenticated(ex));
                sendJson(ex, 200, body);
            } else if (path.equals("/api/login") && method.equals("POST")) {
                login(ex);
            } else if (path.equals("/api/logout") && method.equals("POST")) {
                if (!mutationAllowed(ex)) return;
                final String token = sessionToken(ex);
                if (token != null) sessions.remove(token);
                ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict");
                sendJson(ex, 200, ok("Signed out"));
            } else if (path.startsWith("/api/")) {
                api(ex, path, method);
            } else {
                sendJson(ex, 404, error("Not found"));
            }
        } catch (RuntimeException ex2) {
            TerminalLogger.error("Dashboard request failed: " + ex2);
        }
    }

    private void api(final HttpExchange ex, final String path, final String method) throws IOException {
        if (authRequired && !isAuthenticated(ex)) {
            sendJson(ex, 401, error("Sign in required"));
            return;
        }
        if (path.equals("/api/state") && method.equals("GET")) {
            sendJson(ex, 200, state(ex));
        } else if (path.equals("/api/config") && method.equals("GET")) {
            sendJson(ex, 200, configView());
        } else if (path.equals("/api/action") && method.equals("POST")) {
            if (!mutationAllowed(ex)) return;
            final Map<String, Object> body = readBody(ex);
            if (body == null) return;
            final Result result = perform(body, clientIp(ex));
            sendJson(ex, result.ok() ? 200 : 400, result.ok() ? ok(result.message()) : error(result.message()));
        } else {
            sendJson(ex, 404, error("Not found"));
        }
    }

    // ------------------------------------------------------------------ auth

    private void login(final HttpExchange ex) throws IOException {
        if (!mutationAllowed(ex)) return;
        final String ip = clientIp(ex);
        final long now = System.currentTimeMillis();
        final Attempts previous = attempts.get(ip);
        if (previous != null && previous.lockedUntil() > now) {
            ex.getResponseHeaders().add("Retry-After", String.valueOf((previous.lockedUntil() - now) / 1000 + 1));
            sendJson(ex, 429, error("Too many failed attempts. Try again in a minute."));
            return;
        }
        final Map<String, Object> body = readBody(ex);
        if (body == null) return;

        final String given = body.get("password") instanceof String s ? s : "";
        if (authRequired && !MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), password)) {
            final int failures = (previous == null || previous.lockedUntil() != 0 ? 0 : previous.failures()) + 1;
            final boolean lock = failures >= MAX_LOGIN_FAILURES;
            attempts.put(ip, new Attempts(lock ? 0 : failures, lock ? now + LOGIN_LOCK_MS : 0));
            TerminalLogger.warn("[DASHBOARD] Failed sign-in from " + ip + (lock ? " (locked for 60s)" : ""));
            sendJson(ex, 401, error("Wrong password"));
            return;
        }
        attempts.remove(ip);
        pruneSessions(now);

        final byte[] raw = new byte[32];
        random.nextBytes(raw);
        final String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        sessions.put(token, now + SESSION_TTL_MS);
        ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + token + "; Path=/; Max-Age="
            + (SESSION_TTL_MS / 1000) + "; HttpOnly; SameSite=Strict");
        TerminalLogger.info("[DASHBOARD] Signed in from " + ip);
        sendJson(ex, 200, ok("Signed in"));
    }

    private boolean isAuthenticated(final HttpExchange ex) {
        final String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")
            && MessageDigest.isEqual(auth.substring(7).getBytes(StandardCharsets.UTF_8), password)) {
            return true;
        }
        final String token = sessionToken(ex);
        if (token == null) {
            return false;
        }
        final Long expires = sessions.get(token);
        if (expires == null || expires < System.currentTimeMillis()) {
            sessions.remove(token);
            return false;
        }
        return true;
    }

    private static String sessionToken(final HttpExchange ex) {
        final String header = ex.getRequestHeaders().getFirst("Cookie");
        if (header == null) {
            return null;
        }
        for (final String part : header.split(";")) {
            final String trimmed = part.trim();
            if (trimmed.startsWith(COOKIE + "=")) {
                return trimmed.substring(COOKIE.length() + 1);
            }
        }
        return null;
    }

    private void pruneSessions(final long now) {
        sessions.values().removeIf(expires -> expires < now);
        attempts.values().removeIf(a -> a.lockedUntil() != 0 && a.lockedUntil() < now);
    }

    /** Cross-site request protection for anything that changes state. */
    private boolean mutationAllowed(final HttpExchange ex) throws IOException {
        if (!CSRF_VALUE.equals(ex.getRequestHeaders().getFirst(CSRF_HEADER))) {
            sendJson(ex, 403, error("Missing " + CSRF_HEADER + " header"));
            return false;
        }
        final String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin != null) {
            String authority = null;
            try {
                authority = URI.create(origin).getAuthority();
            } catch (IllegalArgumentException ignored) {
                // treated as mismatch
            }
            final String host = ex.getRequestHeaders().getFirst("Host");
            if (authority == null || host == null || !authority.equalsIgnoreCase(host)) {
                sendJson(ex, 403, error("Cross-origin request refused"));
                return false;
            }
        }
        return true;
    }

    /** Without a password only loopback host names are accepted, which defeats DNS rebinding. */
    private boolean hostAllowed(final HttpExchange ex) {
        if (authRequired) {
            return true;
        }
        final String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null) {
            return false;
        }
        String name = host;
        if (name.startsWith("[")) {
            final int end = name.indexOf(']');
            name = end > 0 ? name.substring(1, end) : name;
        } else if (name.indexOf(':') == name.lastIndexOf(':') && name.contains(":")) {
            name = name.substring(0, name.lastIndexOf(':'));
        }
        name = name.toLowerCase(Locale.ROOT);
        return name.equals("localhost") || name.equals("127.0.0.1") || name.equals("::1");
    }

    private static boolean isLoopbackHost(final String host) {
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (IOException ex) {
            return false;
        }
    }

    private String randomPassword() {
        final String alphabet = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private static String clientIp(final HttpExchange ex) {
        return ex.getRemoteAddress().getAddress().getHostAddress();
    }

    // ------------------------------------------------------------------ state

    private Map<String, Object> state(final HttpExchange ex) {
        final Metrics m = Metrics.INSTANCE;
        final VectorConfig cfg = ctx.config.get();
        final Map<String, String> query = query(ex);
        final long historySeq = longParam(query, "history");
        final long logSeq = longParam(query, "log");

        final Map<String, Object> root = new LinkedHashMap<>();
        root.put("now", System.currentTimeMillis());
        root.put("startedAt", m.startedAtMillis);
        root.put("bind", cfg.bindHost() + ":" + cfg.bindPort());
        root.put("balanceMode", ctx.pool.mode().id());
        root.put("proxyProtocol", cfg.proxyProtocol());

        final Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("connectionsActive", ctx.registry.size());
        totals.put("connectionsTotal", m.connectionsTotal.sum());
        totals.put("bytesFromClients", m.bytesFromClients.sum());
        totals.put("bytesToClients", m.bytesToClients.sum());
        totals.put("noBackend", m.noBackendAvailable.sum());
        root.put("totals", totals);

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
        root.put("status", status);

        final List<Object> backends = new ArrayList<>();
        for (final Backend b : ctx.pool.all()) {
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", b.name());
            entry.put("address", b.address());
            entry.put("weight", b.weight());
            entry.put("healthy", b.healthy());
            entry.put("active", b.activeConnections());
            entry.put("total", b.totalConnections());
            entry.put("failures", b.connectFailures());
            entry.put("latencyMs", b.latencyMs());
            backends.add(entry);
        }
        root.put("backends", backends);

        final List<Object> connections = new ArrayList<>();
        for (final ConnectionRegistry.ConnectionInfo c : ctx.registry.snapshot()) {
            if (connections.size() >= MAX_CONNECTIONS_LISTED) {
                break;
            }
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("ip", c.ip());
            entry.put("port", c.port());
            entry.put("player", c.player());
            entry.put("backend", c.backend());
            entry.put("since", c.connectedAtMillis());
            entry.put("protocol", c.protocol());
            connections.add(entry);
        }
        root.put("connections", connections);

        final List<Object> bans = new ArrayList<>();
        for (final BanList.Entry b : ctx.banList.all()) {
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("target", b.target());
            entry.put("expiresAt", b.expiresAtMillis());
            entry.put("reason", b.reason());
            bans.add(entry);
        }
        root.put("bans", bans);

        final Map<String, Object> whitelist = new LinkedHashMap<>();
        whitelist.put("enabled", cfg.access().whitelistEnabled());
        whitelist.put("entries", ctx.whitelist.entries());
        root.put("whitelist", whitelist);

        final List<Object> points = new ArrayList<>();
        long latestHistory = historySeq;
        for (final MetricsHistory.Point p : ctx.history.since(historySeq)) {
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("seq", p.seq());
            entry.put("t", p.timeMillis());
            entry.put("active", p.activeConnections());
            entry.put("newConns", round(p.newConnectionsPerSec()));
            entry.put("inBps", round(p.bytesInPerSec()));
            entry.put("outBps", round(p.bytesOutPerSec()));
            entry.put("blocked", round(p.blockedPerSec()));
            entry.put("status", round(p.statusPerSec()));
            points.add(entry);
            latestHistory = p.seq();
        }
        root.put("history", points);
        root.put("historySeq", latestHistory);

        final List<Object> logs = new ArrayList<>();
        long latestLog = logSeq;
        for (final gg.literal.log.TerminalLogger.LogEntry l : TerminalLogger.recent(logSeq, logSeq == 0 ? 200 : 500)) {
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("seq", l.seq());
            entry.put("t", l.timeMillis());
            entry.put("level", l.level());
            entry.put("message", l.message());
            logs.add(entry);
            latestLog = l.seq();
        }
        root.put("logs", logs);
        root.put("logSeq", latestLog);
        return root;
    }

    /** Current settings as in config.json, with secrets replaced by whether they are set. */
    private Map<String, Object> configView() {
        final VectorConfig c = ctx.config.get();
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("bind-host", c.bindHost());
        out.put("bind-port", c.bindPort());
        final List<Object> backends = new ArrayList<>();
        for (final VectorConfig.BackendTarget t : c.backends().targets()) {
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", t.name());
            entry.put("host", t.host());
            entry.put("port", t.port());
            entry.put("weight", t.weight());
            backends.add(entry);
        }
        out.put("backends", backends);
        out.put("balance-mode", c.backends().mode().id());
        out.put("health-check-interval-ms", c.backends().healthIntervalMs());
        out.put("health-check-timeout-ms", c.backends().healthTimeoutMs());
        out.put("health-check-failures", c.backends().healthFailThreshold());
        out.put("max-connect-attempts", c.backends().maxConnectAttempts());
        out.put("boss-threads", c.bossThreads());
        out.put("worker-threads", c.workerThreads());
        out.put("max-bytes-per-second", c.maxBytesPerSecond());
        out.put("max-packets-per-second", c.maxPacketsPerSecond());
        out.put("connect-timeout-ms", c.connectTimeoutMs());
        out.put("proxy-protocol", c.proxyProtocol());
        out.put("max-connections-per-ip", c.limits().maxConnectionsPerIp());
        out.put("max-connection-rate-per-ip", c.limits().maxConnectionRatePerIp());
        out.put("connection-rate-window-ms", c.limits().connectionRateWindowMs());
        out.put("status-cache-enabled", c.status().cacheEnabled());
        out.put("status-cache-ttl-ms", c.status().cacheTtlMs());
        out.put("status-sum-players", c.status().sumPlayers());
        out.put("offline-motd", c.status().offlineMotd());
        out.put("whitelist-enabled", c.access().whitelistEnabled());
        out.put("whitelist-message", c.access().whitelistMessage());
        out.put("metrics-enabled", c.metrics().enabled());
        out.put("metrics-host", c.metrics().host());
        out.put("metrics-port", c.metrics().port());
        out.put("metrics-token", c.metrics().token().isEmpty() ? "(not set)" : "(set)");
        out.put("dashboard-enabled", c.dashboard().enabled());
        out.put("dashboard-host", c.dashboard().host());
        out.put("dashboard-port", c.dashboard().port());
        out.put("dashboard-password", authRequired ? "(set)" : "(not set)");
        return out;
    }

    // ------------------------------------------------------------------ actions

    private Result perform(final Map<String, Object> body, final String actor) {
        final String action = str(body, "action");
        switch (action) {
            case "kick" -> {
                final String ip = str(body, "ip");
                if (ip.isEmpty()) return new Result(false, "Enter the IP to disconnect.");
                final int count = ctx.registry.kickByIp(ip);
                log(actor, "kick " + ip + " (" + count + " connection(s))");
                return count > 0
                    ? new Result(true, "Disconnected " + count + " connection(s) from " + ip + ".")
                    : new Result(false, "No open connections from " + ip + ".");
            }
            case "ban" -> {
                final String target = str(body, "target");
                if (target.isEmpty()) return new Result(false, "Enter an IP address or CIDR block to ban.");
                long duration = 0L;
                final String durationText = str(body, "duration");
                if (!durationText.isEmpty() && !durationText.equalsIgnoreCase("permanent")) {
                    duration = Durations.parseMillis(durationText);
                    if (duration <= 0) {
                        return new Result(false, "Duration must look like 30m, 12h, 7d or 2w, or be left empty for a permanent ban.");
                    }
                }
                final BanList.Entry entry = ctx.banList.ban(target, duration, str(body, "reason"));
                if (entry == null) return new Result(false, "\"" + target + "\" is not a valid IP address or CIDR block.");
                final int kicked = ctx.registry.kickIf(ctx.banList::isBanned);
                log(actor, "ban " + entry.target() + (duration > 0 ? " for " + durationText : "") + " " + entry.reason());
                return new Result(true, "Banned " + entry.target() + (kicked > 0 ? " and disconnected " + kicked + " connection(s)." : "."));
            }
            case "unban" -> {
                final String target = str(body, "target");
                if (!ctx.banList.unban(target)) return new Result(false, "No ban for " + target + ".");
                log(actor, "unban " + target);
                return new Result(true, "Removed the ban on " + target + ".");
            }
            case "whitelist-add" -> {
                final String entry = str(body, "entry");
                if (entry.isEmpty()) return new Result(false, "Enter a username, IP or CIDR block.");
                final String stored = ctx.whitelist.add(entry);
                if (stored == null) return new Result(false, entry + " is already whitelisted or not valid.");
                log(actor, "whitelist add " + stored);
                return new Result(true, "Added " + stored + " to the whitelist.");
            }
            case "whitelist-remove" -> {
                final String entry = str(body, "entry");
                if (!ctx.whitelist.remove(entry)) return new Result(false, entry + " is not on the whitelist.");
                log(actor, "whitelist remove " + entry);
                return new Result(true, "Removed " + entry + " from the whitelist.");
            }
            case "send" -> {
                final String ip = str(body, "ip");
                final Backend backend = ctx.pool.findByName(str(body, "backend"));
                if (ip.isEmpty() || backend == null) return new Result(false, "Pick a connected player and a backend.");
                final int count = ctx.registry.sendToBackend(ip, backend);
                log(actor, "send " + ip + " -> " + backend.name());
                return new Result(true, "Disconnected " + count + " session(s) from " + ip
                    + "; their next login goes to " + backend.name() + ".");
            }
            case "reload" -> {
                try {
                    final List<String> restart = ctx.reload();
                    log(actor, "reload");
                    return new Result(true, "Configuration reloaded."
                        + (restart.isEmpty() ? "" : " Restart Vector to apply: " + String.join(", ", restart) + "."));
                } catch (BootstrapException ex) {
                    return new Result(false, "Reload failed, the previous configuration is still active: " + ex.getMessage());
                }
            }
            default -> {
                return new Result(false, "Unknown action.");
            }
        }
    }

    private static void log(final String actor, final String what) {
        TerminalLogger.info("[DASHBOARD] " + actor + ": " + what);
    }

    private static String str(final Map<String, Object> body, final String key) {
        return body.get(key) instanceof String s ? s.trim() : "";
    }

    // ------------------------------------------------------------------ HTTP plumbing

    private void loadAssets() throws IOException {
        assets.put("index.html", load("index.html", "text/html; charset=utf-8"));
        assets.put("app.js", load("app.js", "text/javascript; charset=utf-8"));
        assets.put("style.css", load("style.css", "text/css; charset=utf-8"));
    }

    private static Asset load(final String name, final String type) throws IOException {
        try (InputStream in = DashboardServer.class.getResourceAsStream("/dashboard/" + name)) {
            if (in == null) {
                throw new IOException("missing resource /dashboard/" + name);
            }
            return new Asset(in.readAllBytes(), type);
        }
    }

    private void sendAsset(final HttpExchange ex, final String name) throws IOException {
        final Asset asset = assets.get(name);
        ex.getResponseHeaders().add("Content-Type", asset.contentType());
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, asset.bytes().length);
        ex.getResponseBody().write(asset.bytes());
    }

    private static void sendJson(final HttpExchange ex, final int status, final Object body) throws IOException {
        final byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    private Map<String, Object> readBody(final HttpExchange ex) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        final byte[] chunk = new byte[2048];
        try (InputStream in = ex.getRequestBody()) {
            int n;
            while ((n = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, n);
                if (buffer.size() > MAX_BODY_BYTES) {
                    sendJson(ex, 413, error("Request body too large"));
                    return null;
                }
            }
        }
        try {
            return buffer.size() == 0 ? new LinkedHashMap<>() : Json.parseObject(buffer.toString(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, error("Request body is not valid JSON"));
            return null;
        }
    }

    private static Map<String, String> query(final HttpExchange ex) {
        final Map<String, String> out = new HashMap<>();
        final String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) {
            return out;
        }
        for (final String pair : raw.split("&")) {
            final int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return out;
    }

    private static long longParam(final Map<String, String> query, final String key) {
        try {
            return Math.max(0L, Long.parseLong(query.getOrDefault(key, "0")));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private static double round(final double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static Map<String, Object> ok(final String message) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("message", message);
        return body;
    }

    private static Map<String, Object> error(final String message) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("message", message);
        return body;
    }
}

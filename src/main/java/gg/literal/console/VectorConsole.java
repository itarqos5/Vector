package gg.literal.console;

import gg.literal.log.TerminalLogger;
import gg.literal.runtime.Backend;
import gg.literal.runtime.BackendPool;
import gg.literal.runtime.BanList;
import gg.literal.runtime.Metrics;
import gg.literal.runtime.VectorContext;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VectorConsole {

    private static final String ANSI_RED   = "\u001B[31m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_RESET = "\u001B[0m";

    private static final Pattern DURATION = Pattern.compile("(\\d+)([smhdw])");

    private final VectorContext ctx;

    public VectorConsole(final VectorContext ctx) {
        this.ctx = ctx;
    }

    public void start() {
        final Thread thread = new Thread(this::loop, "vector-console");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        try (final BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            while (true) {
                printPrompt();
                final String line = reader.readLine();
                if (line == null) break;
                final String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    process(trimmed);
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** Prints the {@code > } prompt without a newline. */
    private static void printPrompt() {
        if (TerminalLogger.isAnsiEnabled()) {
            TerminalLogger.printRaw(ANSI_GREEN + "> " + ANSI_RESET);
        } else {
            TerminalLogger.printRaw("> ");
        }
    }

    /**
     * When a command is invalid, moves the cursor up one line and reprints the
     * input in red so the user gets immediate visual feedback.
     * Only called when ANSI is enabled.
     */
    private static void highlightInvalidCommand(final String typed) {
        // \033[A = cursor up, \r = line start, then red prompt+command, then reset+newline
        TerminalLogger.printRaw("\033[A\r" + ANSI_RED + "> " + typed + ANSI_RESET + "\n");
    }

    private void process(final String input) {
        final String[] parts = input.split("\\s+");
        final String command = parts[0].toLowerCase();

        switch (command) {
            case "help" -> printHelp();

            case "list" -> {
                final List<String> connections = ctx.registry.listConnections();
                if (connections.isEmpty()) {
                    TerminalLogger.info("No active connections.");
                } else {
                    TerminalLogger.info("Active connections (" + connections.size() + "):");
                    for (final String entry : connections) {
                        TerminalLogger.info("  " + entry);
                    }
                }
            }

            case "backends" -> backends();
            case "stats" -> stats();

            case "kick" -> {
                if (parts.length < 2) { TerminalLogger.warn("Usage: kick <ip>"); return; }
                final String ip = parts[1];
                final int count = ctx.registry.kickByIp(ip);
                if (count > 0) {
                    TerminalLogger.info("Kicked " + count + " connection(s) from " + ip);
                } else {
                    TerminalLogger.warn("No active connections found for IP: " + ip);
                }
            }

            case "send" -> send(parts);

            case "ban" -> ban(parts);
            case "unban" -> unban(parts);
            case "banlist" -> banlist();
            case "whitelist" -> whitelist(parts);

            case "stop" -> {
                TerminalLogger.info("Stopping Vector...");
                System.exit(0);
            }

            default -> {
                if (TerminalLogger.isAnsiEnabled()) {
                    highlightInvalidCommand(parts[0]);
                }
                TerminalLogger.warn("Unknown command: \"" + parts[0] + "\". Type 'help' for the command list.");
            }
        }
    }

    private void send(final String[] parts) {
        if (parts.length == 3) {
            final Backend backend = ctx.pool.findByName(parts[2]);
            if (backend == null) {
                TerminalLogger.warn("Unknown backend \"" + parts[2] + "\". Use 'backends' to list them.");
                return;
            }
            final int count = ctx.registry.sendToBackend(parts[1], backend);
            TerminalLogger.info("[SEND] " + parts[1] + " -> " + BackendPool.describe(backend)
                + " (disconnected " + count + " session(s); their next login lands there)");
            return;
        }
        if (parts.length < 4) {
            TerminalLogger.warn("Usage: send <ip> <backend-name>  |  send <ip> <host> <port>");
            return;
        }
        final String ip = parts[1];
        final String destHost = parts[2];
        final int destPort;
        try {
            destPort = Integer.parseInt(parts[3]);
        } catch (NumberFormatException ex) {
            TerminalLogger.warn("Invalid port: " + parts[3]);
            return;
        }
        ctx.registry.sendToServer(ip, destHost, destPort);
        TerminalLogger.info("[SEND] Transferring " + ip + " -> " + destHost + ":" + destPort);
    }

    private void ban(final String[] parts) {
        if (parts.length < 2) {
            TerminalLogger.warn("Usage: ban <ip|cidr> [duration e.g. 30m, 12h, 7d] [reason...]");
            return;
        }
        long durationMillis = 0L;
        int reasonStart = 2;
        if (parts.length > 2) {
            final long parsed = parseDuration(parts[2]);
            if (parsed > 0) {
                durationMillis = parsed;
                reasonStart = 3;
            }
        }
        final String reason = String.join(" ", Arrays.copyOfRange(parts, Math.min(reasonStart, parts.length), parts.length));

        final BanList.Entry entry = ctx.banList.ban(parts[1], durationMillis, reason);
        if (entry == null) {
            TerminalLogger.warn("Not a valid IP address or CIDR block: " + parts[1]);
            return;
        }
        TerminalLogger.info("Banned " + describe(entry));
        final int kicked = ctx.registry.kickIf(ctx.banList::isBanned);
        if (kicked > 0) TerminalLogger.info("Kicked " + kicked + " active connection(s)");
    }

    private void unban(final String[] parts) {
        if (parts.length < 2) { TerminalLogger.warn("Usage: unban <ip|cidr>"); return; }
        if (ctx.banList.unban(parts[1])) {
            TerminalLogger.info("Unbanned: " + parts[1]);
        } else {
            TerminalLogger.warn("Not found in ban list: " + parts[1]);
        }
    }

    private void banlist() {
        final List<BanList.Entry> all = ctx.banList.all();
        if (all.isEmpty()) {
            TerminalLogger.info("Ban list is empty.");
        } else {
            TerminalLogger.info("Bans (" + all.size() + "):");
            for (final BanList.Entry entry : all) TerminalLogger.info("  " + describe(entry));
        }
    }

    private static String describe(final BanList.Entry entry) {
        final StringBuilder sb = new StringBuilder(entry.target());
        sb.append(entry.permanent()
            ? " (permanent"
            : " (until " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(entry.expiresAtMillis())));
        if (!entry.reason().isEmpty()) sb.append(": ").append(entry.reason());
        return sb.append(')').toString();
    }

    /** Parses {@code 30s}, {@code 15m}, {@code 12h}, {@code 7d}, {@code 2w}; returns -1 if {@code token} isn't a duration. */
    private static long parseDuration(final String token) {
        final Matcher m = DURATION.matcher(token.toLowerCase());
        if (!m.matches()) return -1L;
        final long amount = Long.parseLong(m.group(1));
        final long unit = switch (m.group(2).charAt(0)) {
            case 's' -> 1_000L;
            case 'm' -> 60_000L;
            case 'h' -> 3_600_000L;
            case 'd' -> 86_400_000L;
            default -> 604_800_000L;
        };
        try {
            return Math.multiplyExact(amount, unit);
        } catch (ArithmeticException ex) {
            return -1L;
        }
    }

    private void whitelist(final String[] parts) {
        final String sub = parts.length > 1 ? parts[1].toLowerCase() : "list";
        switch (sub) {
            case "list" -> {
                final boolean enabled = ctx.config.get().access().whitelistEnabled();
                final List<String> entries = ctx.whitelist.entries();
                TerminalLogger.info("Whitelist is " + (enabled ? "ENABLED" : "disabled (whitelist-enabled=false)")
                    + ", " + entries.size() + " entr" + (entries.size() == 1 ? "y" : "ies") + ":");
                for (final String entry : entries) TerminalLogger.info("  " + entry);
            }
            case "add" -> {
                if (parts.length < 3) { TerminalLogger.warn("Usage: whitelist add <username|ip|cidr>"); return; }
                final String stored = ctx.whitelist.add(parts[2]);
                if (stored == null) {
                    TerminalLogger.warn("Already whitelisted (or invalid): " + parts[2]);
                } else {
                    TerminalLogger.info("Whitelisted: " + stored);
                }
            }
            case "remove" -> {
                if (parts.length < 3) { TerminalLogger.warn("Usage: whitelist remove <username|ip|cidr>"); return; }
                if (ctx.whitelist.remove(parts[2])) {
                    TerminalLogger.info("Removed from whitelist: " + parts[2]);
                } else {
                    TerminalLogger.warn("Not on the whitelist: " + parts[2]);
                }
            }
            default -> TerminalLogger.warn("Usage: whitelist [list|add <entry>|remove <entry>]");
        }
    }

    private void backends() {
        final List<Backend> all = ctx.pool.all();
        TerminalLogger.info("Backends (" + all.size() + ", " + ctx.pool.mode().id() + "):");
        for (final Backend b : all) {
            TerminalLogger.info(String.format("  %-14s %-24s %-5s weight=%-3d conns=%-4d total=%-6d fails=%-4d latency=%s",
                b.name(), b.address(), b.healthy() ? "UP" : "DOWN", b.weight(), b.activeConnections(),
                b.totalConnections(), b.connectFailures(), b.latencyMs() >= 0 ? b.latencyMs() + "ms" : "-"));
        }
    }

    private void stats() {
        final Metrics m = Metrics.INSTANCE;
        final long uptime = (System.currentTimeMillis() - m.startedAtMillis) / 1000;
        TerminalLogger.info("Uptime " + (uptime / 3600) + "h " + ((uptime / 60) % 60) + "m " + (uptime % 60) + "s"
            + " | connections active=" + ctx.registry.size() + " total=" + m.connectionsTotal.sum());
        TerminalLogger.info("Traffic: from clients " + bytes(m.bytesFromClients.sum())
            + ", to clients " + bytes(m.bytesToClients.sum()));
        TerminalLogger.info("Blocked: ban=" + m.blockedBan.sum()
            + " conn-limit=" + m.blockedConnectionLimit.sum()
            + " conn-rate=" + m.blockedConnectionRate.sum()
            + " packet-limit=" + m.blockedPacketLimit.sum()
            + " whitelist=" + m.blockedWhitelist.sum());
        TerminalLogger.info("Status pings: cache=" + m.statusFromCache.sum()
            + " offline=" + m.statusOffline.sum()
            + " backend=" + m.statusFromBackend.sum()
            + " | no-backend drops=" + m.noBackendAvailable.sum());
    }

    private static String bytes(final long value) {
        if (value < 1024) return value + " B";
        final String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double v = value;
        int unit = -1;
        while (v >= 1024 && unit < units.length - 1) {
            v /= 1024;
            unit++;
        }
        return String.format("%.1f %s", v, units[unit]);
    }

    private static void printHelp() {
        TerminalLogger.info("Vector commands:");
        TerminalLogger.info("  list                          - list active connections (player, backend)");
        TerminalLogger.info("  backends                      - show backend health, load and latency");
        TerminalLogger.info("  stats                         - traffic, blocks and status ping counters");
        TerminalLogger.info("  kick <ip>                     - disconnect a player by IP");
        TerminalLogger.info("  send <ip> <backend>           - disconnect and route the next login to a named backend");
        TerminalLogger.info("  send <ip> <host> <port>        - transfer the client to another server (1.20.5+)");
        TerminalLogger.info("  ban <ip|cidr> [30m|12h|7d] [reason...] - ban (optionally timed) and kick");
        TerminalLogger.info("  unban <ip|cidr>               - remove a ban");
        TerminalLogger.info("  banlist                       - show all bans");
        TerminalLogger.info("  whitelist [list|add|remove]   - manage whitelist.txt (needs whitelist-enabled)");
        TerminalLogger.info("  stop                          - gracefully shut down Vector");
    }
}

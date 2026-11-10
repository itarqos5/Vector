package gg.literal.console;

import gg.literal.log.TerminalLogger;
import gg.literal.runtime.Backend;
import gg.literal.runtime.BackendPool;
import gg.literal.runtime.VectorContext;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;

public final class VectorConsole {

    private static final String ANSI_RED   = "\u001B[31m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_RESET = "\u001B[0m";

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

            case "ban" -> {
                if (parts.length < 2) { TerminalLogger.warn("Usage: ban <ip>"); return; }
                final String ip = parts[1];
                ctx.banList.ban(ip);
                TerminalLogger.info("Banned: " + ip);
                final int kicked = ctx.registry.kickByIp(ip);
                if (kicked > 0) TerminalLogger.info("Kicked " + kicked + " active connection(s) from " + ip);
            }

            case "unban" -> {
                if (parts.length < 2) { TerminalLogger.warn("Usage: unban <ip>"); return; }
                final String ip = parts[1];
                if (ctx.banList.unban(ip)) {
                    TerminalLogger.info("Unbanned: " + ip);
                } else {
                    TerminalLogger.warn("IP not found in ban list: " + ip);
                }
            }

            case "banlist" -> {
                final var all = ctx.banList.all();
                if (all.isEmpty()) {
                    TerminalLogger.info("Ban list is empty.");
                } else {
                    TerminalLogger.info("Banned IPs (" + all.size() + "):");
                    for (final String ip : all) TerminalLogger.info("  " + ip);
                }
            }

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

    private void backends() {
        final List<Backend> all = ctx.pool.all();
        TerminalLogger.info("Backends (" + all.size() + ", " + ctx.pool.mode().id() + "):");
        for (final Backend b : all) {
            TerminalLogger.info(String.format("  %-14s %-24s %-5s weight=%-3d conns=%-4d total=%-6d fails=%-4d latency=%s",
                b.name(), b.address(), b.healthy() ? "UP" : "DOWN", b.weight(), b.activeConnections(),
                b.totalConnections(), b.connectFailures(), b.latencyMs() >= 0 ? b.latencyMs() + "ms" : "-"));
        }
    }

    private static void printHelp() {
        TerminalLogger.info("Vector commands:");
        TerminalLogger.info("  list                          - list active connections (backend)");
        TerminalLogger.info("  backends                      - show backend health, load and latency");
        TerminalLogger.info("  kick <ip>                     - disconnect a player by IP");
        TerminalLogger.info("  send <ip> <backend>           - disconnect and route the next login to a named backend");
        TerminalLogger.info("  send <ip> <host> <port>        - transfer the client to another server (1.20.5+)");
        TerminalLogger.info("  ban <ip>                      - ban IP and kick any active session");
        TerminalLogger.info("  unban <ip>                    - remove IP from ban list");
        TerminalLogger.info("  banlist                       - show all banned IPs");
        TerminalLogger.info("  stop                          - gracefully shut down Vector");
    }
}

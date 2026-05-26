package gg.literal.console;

import gg.literal.log.TerminalLogger;
import gg.literal.runtime.BanList;
import gg.literal.runtime.ConnectionRegistry;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.util.List;

public final class VectorConsole {

    private static final String ANSI_RED   = "\u001B[31m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_RESET = "\u001B[0m";

    private final ConnectionRegistry registry;
    private final BanList banList;

    public VectorConsole(final ConnectionRegistry registry, final BanList banList) {
        this.registry = registry;
        this.banList = banList;
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
                final List<String> connections = registry.listConnections();
                if (connections.isEmpty()) {
                    TerminalLogger.info("No active connections.");
                } else {
                    TerminalLogger.info("Active connections (" + connections.size() + "):");
                    for (final String entry : connections) {
                        TerminalLogger.info("  " + entry);
                    }
                }
            }

            case "kick" -> {
                if (parts.length < 2) { TerminalLogger.warn("Usage: kick <ip>"); return; }
                final String ip = parts[1];
                final int count = registry.kickByIp(ip);
                if (count > 0) {
                    TerminalLogger.info("Kicked " + count + " connection(s) from " + ip);
                } else {
                    TerminalLogger.warn("No active connections found for IP: " + ip);
                }
            }

            case "send" -> {
                if (parts.length < 4) { TerminalLogger.warn("Usage: send <ip> <host> <port>"); return; }
                final String ip = parts[1];
                final String destHost = parts[2];
                final int destPort;
                try {
                    destPort = Integer.parseInt(parts[3]);
                } catch (NumberFormatException ex) {
                    TerminalLogger.warn("Invalid port: " + parts[3]);
                    return;
                }
                registry.setNextBackend(ip, new InetSocketAddress(destHost, destPort));
                final int kicked = registry.kickByIp(ip);
                if (kicked > 0) {
                    TerminalLogger.info("Disconnected " + ip + " -> will reconnect to " + destHost + ":" + destPort);
                } else {
                    TerminalLogger.info("No active session for " + ip + " -- override set for next connection to " + destHost + ":" + destPort);
                }
            }

            case "ban" -> {
                if (parts.length < 2) { TerminalLogger.warn("Usage: ban <ip>"); return; }
                final String ip = parts[1];
                banList.ban(ip);
                TerminalLogger.info("Banned: " + ip);
                final int kicked = registry.kickByIp(ip);
                if (kicked > 0) TerminalLogger.info("Kicked " + kicked + " active connection(s) from " + ip);
            }

            case "unban" -> {
                if (parts.length < 2) { TerminalLogger.warn("Usage: unban <ip>"); return; }
                final String ip = parts[1];
                if (banList.unban(ip)) {
                    TerminalLogger.info("Unbanned: " + ip);
                } else {
                    TerminalLogger.warn("IP not found in ban list: " + ip);
                }
            }

            case "banlist" -> {
                final var all = banList.all();
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

    private static void printHelp() {
        TerminalLogger.info("Vector commands:");
        TerminalLogger.info("  list                     - list active connections");
        TerminalLogger.info("  kick <ip>                - disconnect a player by IP");
        TerminalLogger.info("  send <ip> <host> <port>  - kick and reroute to another backend on reconnect");
        TerminalLogger.info("  ban <ip>                 - ban IP and kick any active session");
        TerminalLogger.info("  unban <ip>               - remove IP from ban list");
        TerminalLogger.info("  banlist                  - show all banned IPs");
        TerminalLogger.info("  stop                     - gracefully shut down Vector");
    }
}

package gg.literal.log;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public final class TerminalLogger {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final Object LOCK = new Object();

    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";
    private static final String BRIGHT_RED_BG = "\u001B[101m";
    private static final String FLASH = "\u001B[5m";

    private TerminalLogger() {
    }

    public static void info(final String message) {
        log("INFO", GREEN, CYAN, message);
    }

    public static void warn(final String message) {
        log("WARN", YELLOW, YELLOW, message);
    }

    public static void error(final String message) {
        log("ERROR", RED, RED, message);
    }

    public static void fatal(final String message) {
        final String timestamp = LocalTime.now().format(FORMATTER);
        final String level = BRIGHT_RED_BG + FLASH + "[FATAL]" + RESET;
        synchronized (LOCK) {
            System.err.println("[" + timestamp + "] " + level + " " + message);
        }
        System.exit(1);
    }

    private static void log(final String level, final String levelColor, final String messageColor, final String message) {
        final String timestamp = LocalTime.now().format(FORMATTER);
        final String colorizedLevel = levelColor + "[" + level + "]" + RESET;
        synchronized (LOCK) {
            System.out.println("[" + timestamp + "] " + colorizedLevel + " " + messageColor + message + RESET);
        }
    }
}

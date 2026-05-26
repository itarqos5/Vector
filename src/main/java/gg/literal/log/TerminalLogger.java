package gg.literal.log;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public final class TerminalLogger {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final Object LOCK = new Object();
    private static final boolean ANSI_ENABLED = detectAnsiSupport();

    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";
    private static final String BRIGHT_RED_BG = "\u001B[101m";
    private static final String FLASH = "\u001B[5m";

    private TerminalLogger() {
    }

    public static boolean isAnsiEnabled() {
        return ANSI_ENABLED;
    }

    /** Prints raw text under the logger lock so it doesn't interleave with log lines. */
    public static void printRaw(final String text) {
        synchronized (LOCK) {
            System.out.print(text);
            System.out.flush();
        }
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
        final String level = colorize("[FATAL]", BRIGHT_RED_BG + FLASH);
        synchronized (LOCK) {
            System.err.println("[" + timestamp + "] " + level + " " + message);
        }
        System.exit(1);
    }

    private static void log(final String level, final String levelColor, final String messageColor, final String message) {
        final String timestamp = LocalTime.now().format(FORMATTER);
        final String colorizedLevel = colorize("[" + level + "]", levelColor);
        final String colorizedMessage = colorize(message, messageColor);
        synchronized (LOCK) {
            System.out.println("[" + timestamp + "] " + colorizedLevel + " " + colorizedMessage);
        }
    }

    private static String colorize(final String value, final String colorCode) {
        if (!ANSI_ENABLED) {
            return value;
        }
        return colorCode + value + RESET;
    }

    // -------------------------------------------------------------------------
    // ANSI detection
    // -------------------------------------------------------------------------

    private static boolean detectAnsiSupport() {
        final String override = System.getenv("VECTOR_ANSI");
        if (override != null) {
            if ("true".equalsIgnoreCase(override) || "1".equals(override)) return true;
            if ("false".equalsIgnoreCase(override) || "0".equals(override)) return false;
        }

        if (System.console() == null) {
            return false;
        }

        final String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) {
            return true; // Unix/macOS — ANSI always works on a real console
        }

        // Windows: try to enable Virtual Terminal Processing in the active console.
        // This uses the Java 21 FFM API to call kernel32.dll — requires
        // --enable-native-access=ALL-UNNAMED.  The call is wrapped in a broad
        // catch so it degrades silently if access is denied.
        if (tryEnableWindowsVirtualTerminal()) {
            return true;
        }

        // Fallback: known VT-capable terminal emulators
        if (System.getenv("WT_SESSION") != null) return true;          // Windows Terminal
        if (System.getenv("ANSICON") != null) return true;             // ANSICON wrapper
        if ("ON".equalsIgnoreCase(System.getenv("ConEmuANSI"))) return true; // ConEmu/Cmder
        final String term = System.getenv("TERM");
        if (term != null && term.toLowerCase().contains("xterm")) return true;
        if (System.getenv("PSModulePath") != null) return true;        // PowerShell

        return false;
    }

    /**
     * Calls kernel32 SetConsoleMode to add ENABLE_VIRTUAL_TERMINAL_PROCESSING (0x0004)
     * to the stdout console handle.  Requires {@code --enable-native-access=ALL-UNNAMED}.
     * Any failure (including missing native access) returns {@code false}.
     */
    private static boolean tryEnableWindowsVirtualTerminal() {
        try {
            final java.lang.foreign.Arena arena = java.lang.foreign.Arena.global();
            final java.lang.foreign.SymbolLookup kernel32 =
                java.lang.foreign.SymbolLookup.libraryLookup("kernel32", arena);
            final java.lang.foreign.Linker linker = java.lang.foreign.Linker.nativeLinker();

            // HANDLE GetStdHandle(DWORD nStdHandle)
            final java.lang.invoke.MethodHandle getStdHandle = linker.downcallHandle(
                kernel32.find("GetStdHandle").orElseThrow(),
                java.lang.foreign.FunctionDescriptor.of(
                    java.lang.foreign.ValueLayout.ADDRESS,
                    java.lang.foreign.ValueLayout.JAVA_INT));

            // BOOL GetConsoleMode(HANDLE hConsoleOutput, LPDWORD lpMode)
            final java.lang.invoke.MethodHandle getConsoleMode = linker.downcallHandle(
                kernel32.find("GetConsoleMode").orElseThrow(),
                java.lang.foreign.FunctionDescriptor.of(
                    java.lang.foreign.ValueLayout.JAVA_INT,
                    java.lang.foreign.ValueLayout.ADDRESS,
                    java.lang.foreign.ValueLayout.ADDRESS));

            // BOOL SetConsoleMode(HANDLE hConsoleOutput, DWORD dwMode)
            final java.lang.invoke.MethodHandle setConsoleMode = linker.downcallHandle(
                kernel32.find("SetConsoleMode").orElseThrow(),
                java.lang.foreign.FunctionDescriptor.of(
                    java.lang.foreign.ValueLayout.JAVA_INT,
                    java.lang.foreign.ValueLayout.ADDRESS,
                    java.lang.foreign.ValueLayout.JAVA_INT));

            // STD_OUTPUT_HANDLE = -11
            final java.lang.foreign.MemorySegment stdOut =
                (java.lang.foreign.MemorySegment) getStdHandle.invoke(-11);

            try (final java.lang.foreign.Arena local = java.lang.foreign.Arena.ofConfined()) {
                final java.lang.foreign.MemorySegment modePtr =
                    local.allocate(java.lang.foreign.ValueLayout.JAVA_INT);
                if ((int) getConsoleMode.invoke(stdOut, modePtr) == 0) return false;
                final int current = modePtr.get(java.lang.foreign.ValueLayout.JAVA_INT, 0);
                // ENABLE_VIRTUAL_TERMINAL_PROCESSING = 0x0004
                return (int) setConsoleMode.invoke(stdOut, current | 0x0004) != 0;
            }
        } catch (Throwable t) {
            // IllegalCallerException (no native access), UnsatisfiedLinkError, etc.
            return false;
        }
    }
}


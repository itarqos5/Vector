package gg.literal;

import gg.literal.bootstrap.BootstrapException;
import gg.literal.bootstrap.EulaManager;
import gg.literal.bootstrap.HardwareValidator;
import gg.literal.bootstrap.LibraryManager;
import gg.literal.config.VectorConfig;
import gg.literal.config.VectorConfigLoader;
import gg.literal.log.TerminalLogger;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Path;

public final class VectorLauncher {

    private static final String RUNTIME_ENTRYPOINT = "gg.literal.runtime.VectorRuntime";

    private VectorLauncher() {
    }

    public static void main(final String[] args) {
        if (shouldSpawnWindowsConsole(args)) {
            if (spawnWindowsConsole()) {
                return;
            }
        }

        TerminalLogger.info("Starting Vector bootstrap sequence...");

        try {
            TerminalLogger.info("Phase 1/3: EULA verification");
            EulaManager.verifyOrCreate(Path.of("eula.txt"));

            TerminalLogger.info("Phase 2/3: Configuration validation");
            final VectorConfig config = VectorConfigLoader.loadOrCreate(Path.of("config.json"));

            TerminalLogger.info("Phase 3/3: Hardware validation");
            HardwareValidator.validate(config);

            final LibraryManager libraryManager = new LibraryManager(Path.of("libraries"), "4.1.111.Final");
            libraryManager.ensureRequiredNettyLibraries();

            final ClassLoader runtimeClassLoader = libraryManager.createRuntimeClassLoader(VectorLauncher.class);
            libraryManager.invokeRuntime(RUNTIME_ENTRYPOINT, runtimeClassLoader, config);
        } catch (BootstrapException ex) {
            TerminalLogger.fatal(ex.getMessage());
        } catch (Throwable throwable) {
            TerminalLogger.fatal("Unexpected bootstrap failure: " + throwable.getMessage());
        }
    }

    private static boolean shouldSpawnWindowsConsole(final String[] args) {
        if (!isWindows()) {
            return false;
        }
        if (isLikelyContainerRuntime()) {
            return false;
        }
        if (System.console() != null) {
            return false;
        }
        for (String arg : args) {
            if ("--attached-console".equalsIgnoreCase(arg)) {
                return false;
            }
        }
        return getCurrentJarPath() != null;
    }

    private static boolean spawnWindowsConsole() {
        final Path jarPath = getCurrentJarPath();
        if (jarPath == null) {
            return false;
        }

        try {
            new ProcessBuilder(
                "cmd.exe",
                "/c",
                "start",
                "Vector",
                "cmd.exe",
                "/k",
                "java",
                "--enable-native-access=ALL-UNNAMED",
                "-jar",
                jarPath.toAbsolutePath().toString(),
                "--attached-console"
            ).start();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Path getCurrentJarPath() {
        try {
            final File file = new File(VectorLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!file.isFile() || !file.getName().toLowerCase().endsWith(".jar")) {
                return null;
            }
            return file.toPath();
        } catch (URISyntaxException ex) {
            return null;
        }
    }

    private static boolean isWindows() {
        final String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win");
    }

    private static boolean isLikelyContainerRuntime() {
        return System.getenv("PTERODACTYL") != null
            || System.getenv("SERVER_UUID") != null
            || System.getenv("CONTAINER") != null;
    }
}

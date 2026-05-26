package gg.literal;

import gg.literal.bootstrap.BootstrapException;
import gg.literal.bootstrap.EulaManager;
import gg.literal.bootstrap.HardwareValidator;
import gg.literal.bootstrap.LibraryManager;
import gg.literal.config.VectorConfig;
import gg.literal.config.VectorConfigLoader;
import gg.literal.log.TerminalLogger;

import java.nio.file.Path;

public final class VectorLauncher {

    private static final String RUNTIME_ENTRYPOINT = "gg.literal.runtime.VectorRuntime";

    private VectorLauncher() {
    }

    public static void main(final String[] args) {
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
}

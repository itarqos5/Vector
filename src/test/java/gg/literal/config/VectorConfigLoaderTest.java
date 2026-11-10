package gg.literal.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.literal.bootstrap.BootstrapException;
import gg.literal.config.VectorConfig.BalanceMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VectorConfigLoaderTest {

    @TempDir
    Path dir;

    private VectorConfig load(final String json) throws IOException, BootstrapException {
        final Path file = dir.resolve("config.json");
        Files.writeString(file, json);
        return VectorConfigLoader.load(file);
    }

    private String failure(final String json) throws IOException {
        final Path file = dir.resolve("config.json");
        Files.writeString(file, json);
        return assertThrows(BootstrapException.class, () -> VectorConfigLoader.load(file)).getMessage();
    }

    @Test
    void createsAndReloadsTheDefaultFile() throws Exception {
        final Path file = dir.resolve("new.json");
        final VectorConfig created = VectorConfigLoader.loadOrCreate(file);
        assertTrue(Files.exists(file));
        final VectorConfig reloaded = VectorConfigLoader.load(file);
        assertEquals(created.backends(), reloaded.backends());
        assertEquals(created.limits(), reloaded.limits());
        assertEquals(created.status(), reloaded.status());
        assertEquals(created.dashboard(), reloaded.dashboard());
        assertEquals(3000, reloaded.dashboard().port());
    }

    @Test
    void emptyObjectUsesDefaults() throws Exception {
        final VectorConfig cfg = load("{}");
        assertEquals(VectorConfig.defaults().backends(), cfg.backends());
        assertEquals(BalanceMode.ROUND_ROBIN, cfg.backends().mode());
    }

    @Test
    void legacySingleBackendKeysStillWork() throws Exception {
        final VectorConfig cfg = load("""
            {"backend-host": "10.1.2.3", "backend-port": 25570}
            """);
        assertEquals(1, cfg.backends().targets().size());
        assertEquals("main", cfg.backends().targets().get(0).name());
        assertEquals("10.1.2.3:25570", cfg.backends().targets().get(0).address());
    }

    @Test
    void readsBackendArrayAndBalanceMode() throws Exception {
        final VectorConfig cfg = load("""
            {"balance-mode": "least_connections",
             "backends": [
               {"name": "a", "host": "h1", "port": 1000, "weight": 3},
               {"host": "h2", "port": 2000}
             ]}
            """);
        assertEquals(BalanceMode.LEAST_CONNECTIONS, cfg.backends().mode());
        assertEquals(3, cfg.backends().targets().get(0).weight());
        assertEquals("backend-2", cfg.backends().targets().get(1).name());
        assertEquals(1, cfg.backends().targets().get(1).weight());
    }

    @Test
    void readsPerBackendConnectionCaps() throws Exception {
        final VectorConfig cfg = load("""
            {"backends": [
               {"name": "a", "host": "h1", "port": 1000, "max-connections": 80},
               {"name": "b", "host": "h2", "port": 2000}
            ]}
            """);
        assertEquals(80, cfg.backends().targets().get(0).maxConnections());
        assertEquals(0, cfg.backends().targets().get(1).maxConnections());
    }

    @Test
    void knowsEveryBalanceMode() {
        for (final BalanceMode mode : BalanceMode.values()) {
            assertEquals(mode, BalanceMode.parse(mode.id()));
        }
        assertEquals(BalanceMode.LEAST_LATENCY, BalanceMode.parse("LEAST_LATENCY"));
        assertEquals(BalanceMode.RANDOM, BalanceMode.parse(" random "));
    }

    @Test
    void reportsUnknownBalanceModes() throws Exception {
        assertTrue(failure("{\"balance-mode\": \"fastest\"}").contains("balance-mode"));
    }

    @Test
    void rejectsDuplicateBackendNamesAndAddresses() throws Exception {
        assertTrue(failure("""
            {"backends": [{"name":"a","host":"h","port":1},{"name":"A","host":"h2","port":2}]}
            """).contains("duplicate backend name"));
        assertTrue(failure("""
            {"backends": [{"name":"a","host":"h","port":1},{"name":"b","host":"H","port":1}]}
            """).contains("duplicate backend address"));
    }

    @Test
    void rejectsEmptyBackendList() throws Exception {
        assertTrue(failure("{\"backends\": []}").contains("non-empty"));
    }

    @Test
    void validatesRangesAndTypes() throws Exception {
        assertTrue(failure("{\"bind-port\": 70000}").contains("bind-port"));
        assertTrue(failure("{\"proxy-protocol\": \"yes\"}").contains("proxy-protocol"));
        assertTrue(failure("{\"worker-threads\": 1.5}").contains("worker-threads"));
    }

    @Test
    void dashboardPortMustNotClashWithTheProxyPort() throws Exception {
        assertTrue(failure("{\"bind-port\": 3000, \"dashboard-port\": 3000}").contains("dashboard-port"));
    }

    @Test
    void invalidJsonProducesAHelpfulMessage() throws Exception {
        assertTrue(failure("{ nope").contains("not valid JSON"));
    }
}

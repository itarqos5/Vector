package gg.literal.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.literal.bootstrap.BootstrapException;
import gg.literal.config.VectorConfig;
import gg.literal.config.VectorConfigLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigHolderTest {

    @TempDir
    Path dir;

    private ConfigHolder holder() throws Exception {
        final Path file = dir.resolve("config.json");
        return new ConfigHolder(file, VectorConfigLoader.loadOrCreate(file));
    }

    @Test
    void updateWritesTheChangeAndReloads() throws Exception {
        final ConfigHolder holder = holder();
        assertFalse(holder.get().access().whitelistEnabled());
        holder.update(Map.of("whitelist-enabled", true));
        assertTrue(holder.get().access().whitelistEnabled());
        assertTrue(Files.readString(dir.resolve("config.json")).contains("\"whitelist-enabled\": true"));
    }

    @Test
    void updateKeepsUnrelatedKeysAndNotifiesListeners() throws Exception {
        final ConfigHolder holder = holder();
        final AtomicInteger notified = new AtomicInteger();
        holder.onReload(cfg -> notified.incrementAndGet());
        final int port = holder.get().bindPort();
        holder.update(Map.of("max-connections-per-ip", 7));
        assertEquals(port, holder.get().bindPort());
        assertEquals(7, holder.get().limits().maxConnectionsPerIp());
        assertEquals(1, notified.get());
    }

    @Test
    void invalidChangesAreRejectedWithoutTouchingTheFile() throws Exception {
        final ConfigHolder holder = holder();
        final String before = Files.readString(dir.resolve("config.json"));
        assertThrows(BootstrapException.class, () -> holder.update(Map.of("bind-port", 99999)));
        assertEquals(before, Files.readString(dir.resolve("config.json")));
        assertEquals(VectorConfig.defaults().limits(), holder.get().limits());
    }

    @Test
    void updateReportsSettingsThatNeedARestart() throws Exception {
        final ConfigHolder holder = holder();
        final List<String> restart = holder.update(Map.of("dashboard-port", 3100));
        assertTrue(restart.contains("dashboard-port"), restart.toString());
    }

    @Test
    void noTemporaryFileIsLeftBehind() throws Exception {
        holder().update(Map.of("log-chat", false));
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count());
        }
    }
}

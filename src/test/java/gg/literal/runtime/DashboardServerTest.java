package gg.literal.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.literal.config.VectorConfig;
import gg.literal.config.VectorConfigLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DashboardServerTest {

    @TempDir
    Path dir;

    @Test
    void typeMatchingFollowsJsonTypes() {
        assertTrue(DashboardServer.typeMatches("boolean", true));
        assertTrue(DashboardServer.typeMatches("string", "x"));
        assertTrue(DashboardServer.typeMatches("number", 5L));
        assertFalse(DashboardServer.typeMatches("number", 5.5));
        assertFalse(DashboardServer.typeMatches("number", "5"));
        assertFalse(DashboardServer.typeMatches("boolean", "true"));
        assertFalse(DashboardServer.typeMatches("string", null));
        assertFalse(DashboardServer.typeMatches("object", Map.of()));
    }

    @Test
    void secretsAndAccessSettingsCannotBeEditedRemotely() {
        for (final String forbidden : List.of("dashboard-password", "dashboard-host", "dashboard-port", "dashboard-enabled",
            "metrics-token", "webhook-url", "bind-host", "bind-port", "backends", "routes", "allowed-hosts")) {
            assertFalse(DashboardServer.EDITABLE.containsKey(forbidden), forbidden + " must not be editable");
        }
    }

    @Test
    void everyEditableKeyIsAcceptedByTheConfigLoaderWithItsDeclaredType() throws Exception {
        final Path file = dir.resolve("config.json");
        final ConfigHolder holder = new ConfigHolder(file, VectorConfigLoader.loadOrCreate(file));
        for (final Map.Entry<String, String> entry : DashboardServer.EDITABLE.entrySet()) {
            final Object sample = switch (entry.getValue()) {
                case "boolean" -> Boolean.TRUE;
                case "number" -> 7L;
                default -> entry.getKey().equals("balance-mode") ? "least-connections" : "text";
            };
            holder.update(Map.of(entry.getKey(), sample));
        }
        final VectorConfig cfg = holder.get();
        assertTrue(cfg.policy().maintenance());
        assertEquals(7, cfg.limits().maxConnectionsPerIp());
        assertEquals(VectorConfig.BalanceMode.LEAST_CONNECTIONS, cfg.backends().mode());
    }
}

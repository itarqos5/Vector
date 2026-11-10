package gg.literal.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.literal.config.VectorConfig.BalanceMode;
import io.netty.channel.embedded.EmbeddedChannel;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ConnectionRegistryTest {

    private static EmbeddedChannel connection(final ConnectionRegistry registry, final String key, final String backend,
                                              final String player) {
        final EmbeddedChannel channel = new EmbeddedChannel();
        if (backend != null) {
            channel.attr(EuFrontendHandler.ATTR_BACKEND).set(backend);
        }
        if (player != null) {
            channel.attr(MinecraftProtocolSniffer.ATTR_PLAYER_NAME).set(player);
        }
        registry.register(key, channel);
        return channel;
    }

    @Test
    void kickByIpOnlyClosesThatIpUsingTheIndex() {
        final ConnectionRegistry registry = new ConnectionRegistry();
        final EmbeddedChannel a1 = connection(registry, "1.1.1.1:1000", "lobby", "A");
        final EmbeddedChannel a2 = connection(registry, "1.1.1.1:1001", "lobby", "A");
        final EmbeddedChannel b = connection(registry, "2.2.2.2:1000", "lobby", "B");

        assertEquals(2, registry.kickByIp("1.1.1.1"));
        assertFalse(a1.isActive());
        assertFalse(a2.isActive());
        assertTrue(b.isActive());
        assertEquals(0, registry.kickByIp("9.9.9.9"));
    }

    @Test
    void unregisterRemovesTheIndexEntry() {
        final ConnectionRegistry registry = new ConnectionRegistry();
        final EmbeddedChannel a = connection(registry, "1.1.1.1:1000", "lobby", "A");
        registry.unregister("1.1.1.1:1000");
        assertEquals(0, registry.size());
        assertEquals(0, registry.kickByIp("1.1.1.1"));
        assertTrue(a.isActive(), "unregistering does not close the channel");
    }

    @Test
    void kickBackendOnlyClosesConnectionsOnThatBackend() {
        final ConnectionRegistry registry = new ConnectionRegistry();
        final EmbeddedChannel onLobby = connection(registry, "1.1.1.1:1", "Lobby", "A");
        final EmbeddedChannel onSurvival = connection(registry, "2.2.2.2:1", "survival", "B");
        assertEquals(1, registry.kickBackend("lobby"));
        assertFalse(onLobby.isActive());
        assertTrue(onSurvival.isActive());
    }

    @Test
    void evacuateClosesSessionsAndSetsOneShotOverrides() {
        final ConnectionRegistry registry = new ConnectionRegistry();
        final BackendPool pool = new BackendPool(new ConfigHolder(Path.of("x.json"),
            TestConfigs.withBackends(BalanceMode.ROUND_ROBIN, 2, TestConfigs.target("from", 1, 1), TestConfigs.target("to", 2, 1))));
        final EmbeddedChannel p1 = connection(registry, "1.1.1.1:1", "from", "A");
        final EmbeddedChannel p2 = connection(registry, "2.2.2.2:1", "from", "B");
        final EmbeddedChannel other = connection(registry, "3.3.3.3:1", "to", "C");

        assertEquals(2, registry.evacuate("from", pool.findByName("to")));
        assertFalse(p1.isActive());
        assertFalse(p2.isActive());
        assertTrue(other.isActive());

        final InetSocketAddress override = registry.pollNextBackend("1.1.1.1");
        assertNotNull(override);
        assertEquals(2, override.getPort());
        assertNull(registry.pollNextBackend("1.1.1.1"), "overrides are one-shot");
        assertNull(registry.pollNextBackend("3.3.3.3"), "players on other backends are untouched");
    }

    @Test
    void snapshotExposesPlayerBackendAndTraffic() {
        final ConnectionRegistry registry = new ConnectionRegistry();
        final EmbeddedChannel c = connection(registry, "1.1.1.1:25565", "lobby", "Alex");
        c.attr(ConnectionRegistry.ATTR_TRAFFIC).get().fromClient.addAndGet(123);
        c.attr(ConnectionRegistry.ATTR_TRAFFIC).get().toClient.addAndGet(456);

        final ConnectionRegistry.ConnectionInfo info = registry.snapshot().get(0);
        assertEquals("1.1.1.1", info.ip());
        assertEquals(25565, info.port());
        assertEquals("Alex", info.player());
        assertEquals("lobby", info.backend());
        assertEquals(123, info.bytesFromClient());
        assertEquals(456, info.bytesToClient());
    }

    @Test
    void kickIfUsesTheClientIpWithoutThePort() {
        final ConnectionRegistry registry = new ConnectionRegistry();
        final EmbeddedChannel a = connection(registry, "10.0.0.5:4000", null, null);
        final EmbeddedChannel b = connection(registry, "192.168.0.5:4000", null, null);
        assertEquals(1, registry.kickIf(ip -> ip.startsWith("10.")));
        assertFalse(a.isActive());
        assertTrue(b.isActive());
    }
}

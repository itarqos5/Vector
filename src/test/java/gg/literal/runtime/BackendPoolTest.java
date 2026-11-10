package gg.literal.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.literal.config.VectorConfig.BalanceMode;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BackendPoolTest {

    private static BackendPool pool(final BalanceMode mode, final int failures, final gg.literal.config.VectorConfig.BackendTarget... t) {
        return new BackendPool(new ConfigHolder(Path.of("unused.json"), TestConfigs.withBackends(mode, failures, t)));
    }

    private static Map<String, Integer> pick(final BackendPool pool, final int times, final String ip) {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < times; i++) {
            final Backend b = pool.select(ip, Set.of());
            counts.merge(b.name(), 1, Integer::sum);
            pool.release(b);
        }
        return counts;
    }

    @Test
    void roundRobinCyclesEvenly() {
        final BackendPool p = pool(BalanceMode.ROUND_ROBIN, 2,
            TestConfigs.target("a", 1, 1), TestConfigs.target("b", 2, 1), TestConfigs.target("c", 3, 1));
        assertEquals(Map.of("a", 4, "b", 4, "c", 4), pick(p, 12, "1.1.1.1"));
    }

    @Test
    void weightedFollowsWeightsExactlyOverACycle() {
        final BackendPool p = pool(BalanceMode.WEIGHTED, 2,
            TestConfigs.target("a", 1, 5), TestConfigs.target("b", 2, 1), TestConfigs.target("c", 3, 2));
        assertEquals(Map.of("a", 25, "b", 5, "c", 10), pick(p, 40, "1.1.1.1"));
    }

    @Test
    void leastConnectionsPrefersTheLessLoadedBackend() {
        final BackendPool p = pool(BalanceMode.LEAST_CONNECTIONS, 2,
            TestConfigs.target("a", 1, 1), TestConfigs.target("b", 2, 1));
        final Backend first = p.select("1.1.1.1", Set.of());
        final Backend second = p.select("1.1.1.1", Set.of());
        assertFalse(first == second, "second pick must go to the idle backend");
        p.release(first);
        assertSame(first, p.select("1.1.1.1", Set.of()));
    }

    @Test
    void leastConnectionsDividesLoadByWeight() {
        final BackendPool p = pool(BalanceMode.LEAST_CONNECTIONS, 2,
            TestConfigs.target("big", 1, 3), TestConfigs.target("small", 2, 1));
        final Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < 8; i++) {
            counts.merge(p.select("1.1.1.1", Set.of()).name(), 1, Integer::sum);
        }
        assertEquals(6, counts.get("big"));
        assertEquals(2, counts.get("small"));
    }

    @Test
    void ipHashIsStickyPerAddress() {
        final BackendPool p = pool(BalanceMode.IP_HASH, 2,
            TestConfigs.target("a", 1, 1), TestConfigs.target("b", 2, 1), TestConfigs.target("c", 3, 1));
        assertEquals(1, pick(p, 20, "203.0.113.7").size());
    }

    @Test
    void skipsUnhealthyAndExcludedBackends() {
        final BackendPool p = pool(BalanceMode.ROUND_ROBIN, 1,
            TestConfigs.target("a", 1, 1), TestConfigs.target("b", 2, 1));
        final Backend a = p.findByName("a");
        p.report(a, false, -1);
        assertFalse(a.healthy());
        assertEquals(Map.of("b", 6), pick(p, 6, "1.1.1.1"));

        final Set<Backend> excluded = new HashSet<>(Set.of(p.findByName("b")));
        assertNull(p.select("1.1.1.1", excluded), "nothing left once b is excluded and a is down");
    }

    @Test
    void goesDownAfterTheConfiguredNumberOfFailuresAndRecoversOnSuccess() {
        final BackendPool p = pool(BalanceMode.ROUND_ROBIN, 3, TestConfigs.target("a", 1, 1));
        final Backend a = p.findByName("a");
        p.report(a, false, -1);
        p.report(a, false, -1);
        assertTrue(a.healthy(), "two failures are below the threshold of three");
        p.report(a, false, -1);
        assertFalse(a.healthy());
        assertEquals(0, p.healthyCount());
        p.report(a, true, 12);
        assertTrue(a.healthy());
        assertEquals(12, a.latencyMs());
    }

    @Test
    void successResetsTheFailureStreak() {
        final BackendPool p = pool(BalanceMode.ROUND_ROBIN, 2, TestConfigs.target("a", 1, 1));
        final Backend a = p.findByName("a");
        p.report(a, false, -1);
        p.report(a, true, 1);
        p.report(a, false, -1);
        assertTrue(a.healthy());
    }

    @Test
    void applyKeepsStateForUnchangedAddresses() {
        final ConfigHolder holder = new ConfigHolder(Path.of("unused.json"),
            TestConfigs.withBackends(BalanceMode.ROUND_ROBIN, 1, TestConfigs.target("a", 1, 1), TestConfigs.target("b", 2, 1)));
        final BackendPool p = new BackendPool(holder);
        final Backend a = p.findByName("a");
        p.report(a, false, -1);
        p.acquire(a);

        p.apply(TestConfigs.withBackends(BalanceMode.WEIGHTED, 1,
            TestConfigs.target("renamed", 1, 7), TestConfigs.target("c", 3, 1)).backends());

        assertSame(a, p.findByName("renamed"));
        assertFalse(a.healthy());
        assertEquals(1, a.activeConnections());
        assertEquals(7, a.weight());
        assertNull(p.findByName("b"));
        assertEquals(BalanceMode.WEIGHTED, p.mode());
    }

    @Test
    void drainingBackendsKeepSessionsButGetNoNewPlayers() {
        final BackendPool p = pool(BalanceMode.ROUND_ROBIN, 2,
            TestConfigs.target("a", 1, 1), TestConfigs.target("b", 2, 1));
        final Backend a = p.findByName("a");
        p.acquire(a);
        a.setDraining(true);

        assertTrue(a.healthy(), "draining is not the same as down");
        assertFalse(a.available());
        assertEquals(1, p.availableCount());
        assertEquals(2, p.healthyCount());
        assertEquals(1, a.activeConnections(), "existing sessions are untouched");
        assertEquals(Map.of("b", 6), pick(p, 6, "1.1.1.1"));

        a.setDraining(false);
        assertEquals(2, p.availableCount());
    }

    @Test
    void fullBackendsOverflowToTheOthers() {
        final BackendPool p = pool(BalanceMode.ROUND_ROBIN, 2,
            new gg.literal.config.VectorConfig.BackendTarget("small", "127.0.0.1", 1, 1, 2),
            TestConfigs.target("big", 2, 1));
        final Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) { // never released, so the 'small' cap of 2 is eventually hit
            counts.merge(p.select("1.1.1.1", Set.of()).name(), 1, Integer::sum);
        }
        assertEquals(2, counts.get("small"));
        assertEquals(8, counts.get("big"));
    }

    @Test
    void returnsNullWhenEveryBackendIsFull() {
        final BackendPool p = pool(BalanceMode.ROUND_ROBIN, 2,
            new gg.literal.config.VectorConfig.BackendTarget("only", "127.0.0.1", 1, 1, 1));
        assertSame(p.findByName("only"), p.select("1.1.1.1", Set.of()));
        assertNull(p.select("1.1.1.1", Set.of()));
        assertEquals(1, p.availableCount(), "a full backend is still up, which is what the full message keys on");
    }

    @Test
    void randomModeUsesEveryBackend() {
        final BackendPool p = pool(BalanceMode.RANDOM, 2,
            TestConfigs.target("a", 1, 1), TestConfigs.target("b", 2, 1), TestConfigs.target("c", 3, 1));
        final Map<String, Integer> counts = pick(p, 600, "1.1.1.1");
        assertEquals(3, counts.size());
        counts.values().forEach(v -> assertTrue(v > 100, "each backend should get a fair share: " + counts));
    }

    @Test
    void leastLatencyPrefersTheFastestBackendButSpreadsLoad() {
        final BackendPool p = pool(BalanceMode.LEAST_LATENCY, 2,
            TestConfigs.target("fast", 1, 1), TestConfigs.target("slow", 2, 1));
        p.report(p.findByName("fast"), true, 5);
        p.report(p.findByName("slow"), true, 50);

        assertEquals("fast", p.select("1.1.1.1", Set.of()).name());
        // The fast backend now has one open connection: (5+1)*2 = 12 is still better than (50+1)*1 = 51.
        assertEquals("fast", p.select("1.1.1.1", Set.of()).name());
        for (int i = 0; i < 12; i++) {
            p.acquire(p.findByName("fast"));
        }
        // Heavily loaded fast backend: (5+1)*(1+14) = 90 now loses to the idle slow one at 51.
        assertEquals("slow", p.select("1.1.1.1", Set.of()).name());
    }

    @Test
    void markInitialAppliesImmediately() {
        final BackendPool p = pool(BalanceMode.ROUND_ROBIN, 5, TestConfigs.target("a", 1, 1));
        final Backend a = p.findByName("a");
        p.markInitial(a, false, -1);
        assertFalse(a.healthy());
        p.markInitial(a, true, 3);
        assertTrue(a.healthy());
    }
}

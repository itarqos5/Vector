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
    void markInitialAppliesImmediately() {
        final BackendPool p = pool(BalanceMode.ROUND_ROBIN, 5, TestConfigs.target("a", 1, 1));
        final Backend a = p.findByName("a");
        p.markInitial(a, false, -1);
        assertFalse(a.healthy());
        p.markInitial(a, true, 3);
        assertTrue(a.healthy());
    }
}

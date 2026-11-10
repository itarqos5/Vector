package gg.literal.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MetricsTest {

    @Test
    void blockedTotalSumsEveryRefusalReason() {
        final Metrics m = Metrics.INSTANCE;
        final long before = m.blockedTotal();
        m.blockedBan.increment();
        m.blockedConnectionLimit.increment();
        m.blockedConnectionRate.increment();
        m.blockedPacketLimit.increment();
        m.blockedGlobalLimit.increment();
        m.blockedWhitelist.increment();
        m.blockedMaintenance.increment();
        m.blockedProtocol.increment();
        m.blockedHost.increment();
        assertEquals(before + 9, m.blockedTotal());
    }

    @Test
    void peakOnlyEverRises() {
        final Metrics m = Metrics.INSTANCE;
        final int base = m.peakConnections();
        m.observeConnections(base + 5);
        assertEquals(base + 5, m.peakConnections());
        final long at = m.peakConnectionsAt();
        assertTrue(at > 0);

        m.observeConnections(base + 2);
        assertEquals(base + 5, m.peakConnections(), "a lower value must not lower the peak");
        assertEquals(at, m.peakConnectionsAt(), "and must not move the timestamp");
    }
}

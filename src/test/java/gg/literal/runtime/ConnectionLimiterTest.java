package gg.literal.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.literal.config.VectorConfig.Limits;
import org.junit.jupiter.api.Test;

class ConnectionLimiterTest {

    @Test
    void capsConcurrentConnectionsPerIp() {
        final ConnectionLimiter limiter = new ConnectionLimiter();
        final Limits limits = new Limits(2, 0, 10_000);
        assertEquals(ConnectionLimiter.Result.OK, limiter.tryAcquire("1.1.1.1", limits));
        assertEquals(ConnectionLimiter.Result.OK, limiter.tryAcquire("1.1.1.1", limits));
        assertEquals(ConnectionLimiter.Result.TOO_MANY_CONNECTIONS, limiter.tryAcquire("1.1.1.1", limits));
        assertEquals(ConnectionLimiter.Result.OK, limiter.tryAcquire("2.2.2.2", limits), "other IPs are unaffected");

        limiter.release("1.1.1.1");
        assertEquals(ConnectionLimiter.Result.OK, limiter.tryAcquire("1.1.1.1", limits));
    }

    @Test
    void limitsTheRateOfNewConnections() {
        final ConnectionLimiter limiter = new ConnectionLimiter();
        final Limits limits = new Limits(0, 3, 60_000);
        for (int i = 0; i < 3; i++) {
            assertEquals(ConnectionLimiter.Result.OK, limiter.tryAcquire("1.1.1.1", limits));
            limiter.release("1.1.1.1");
        }
        assertEquals(ConnectionLimiter.Result.TOO_FAST, limiter.tryAcquire("1.1.1.1", limits));
    }

    @Test
    void zeroDisablesBothLimits() {
        final ConnectionLimiter limiter = new ConnectionLimiter();
        final Limits limits = new Limits(0, 0, 1000);
        for (int i = 0; i < 500; i++) {
            assertEquals(ConnectionLimiter.Result.OK, limiter.tryAcquire("1.1.1.1", limits));
        }
    }

    @Test
    void rejectedAttemptsDoNotCountAgainstTheCap() {
        final ConnectionLimiter limiter = new ConnectionLimiter();
        final Limits limits = new Limits(1, 0, 1000);
        limiter.tryAcquire("1.1.1.1", limits);
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("1.1.1.1", limits); // rejected
        }
        limiter.release("1.1.1.1");
        assertEquals(ConnectionLimiter.Result.OK, limiter.tryAcquire("1.1.1.1", limits));
    }

    @Test
    void globalCapRefusesEveryoneOnceReached() {
        final ConnectionLimiter limiter = new ConnectionLimiter();
        final Limits limits = new Limits(0, 0, 1000);
        assertEquals(ConnectionLimiter.Result.OK, limiter.tryAcquire("1.1.1.1", limits, 2));
        assertEquals(ConnectionLimiter.Result.OK, limiter.tryAcquire("2.2.2.2", limits, 2));
        assertEquals(ConnectionLimiter.Result.SERVER_FULL, limiter.tryAcquire("3.3.3.3", limits, 2));
        assertEquals(2, limiter.openConnections());
        limiter.release("1.1.1.1");
        assertEquals(ConnectionLimiter.Result.OK, limiter.tryAcquire("3.3.3.3", limits, 2));
    }

    @Test
    void sweepDropsIdleEntries() throws Exception {
        final ConnectionLimiter limiter = new ConnectionLimiter();
        final Limits limits = new Limits(5, 5, 10);
        limiter.tryAcquire("1.1.1.1", limits);
        limiter.release("1.1.1.1");
        Thread.sleep(30);
        limiter.sweep(10);
        assertEquals(0, limiter.trackedIps());
    }
}

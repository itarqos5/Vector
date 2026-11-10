package gg.literal.runtime;

import gg.literal.config.VectorConfig;
import gg.literal.config.VectorConfig.BackendTarget;
import gg.literal.config.VectorConfig.Backends;
import gg.literal.config.VectorConfig.BalanceMode;
import java.util.List;

/** Builders for configs used in tests. */
final class TestConfigs {

    private TestConfigs() {
    }

    static VectorConfig withBackends(final BalanceMode mode, final int failThreshold, final BackendTarget... targets) {
        final VectorConfig d = VectorConfig.defaults();
        final Backends backends = new Backends(List.of(targets), mode, 5000, 2000, failThreshold, 3, VectorConfig.HealthMode.TCP);
        return new VectorConfig(
            d.bindHost(), d.bindPort(), d.bossThreads(), d.workerThreads(), d.maxBytesPerSecond(),
            d.maxPacketsPerSecond(), d.connectTimeoutMs(), d.proxyProtocol(), backends, d.limits(),
            d.status(), d.access(), d.metrics(), d.dashboard(), d.policy());
    }

    static BackendTarget target(final String name, final int port, final int weight) {
        return new BackendTarget(name, "127.0.0.1", port, weight);
    }
}

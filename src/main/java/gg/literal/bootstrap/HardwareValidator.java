package gg.literal.bootstrap;

import gg.literal.config.VectorConfig;

public final class HardwareValidator {

    private HardwareValidator() {
    }

    public static void validate(final VectorConfig config) throws BootstrapException {
        final int availableProcessors = Runtime.getRuntime().availableProcessors();
        final int configuredThreads = config.bossThreads() + config.workerThreads();

        if (configuredThreads > availableProcessors) {
            throw new BootstrapException(
                "Configured thread count exceeds hardware capacity: boss-threads + worker-threads = "
                    + configuredThreads + ", availableProcessors=" + availableProcessors
                    + ". Reduce thread counts in config.json before starting Vector."
            );
        }
    }
}

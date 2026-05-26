package gg.literal.config;

public final class VectorConfig {

    private final String bindHost;
    private final int bindPort;
    private final String backendHost;
    private final int backendPort;
    private final int bossThreads;
    private final int workerThreads;
    private final long maxBytesPerSecond;
    private final long maxPacketsPerSecond;
    private final int connectTimeoutMs;

    public VectorConfig(
        final String bindHost,
        final int bindPort,
        final String backendHost,
        final int backendPort,
        final int bossThreads,
        final int workerThreads,
        final long maxBytesPerSecond,
        final long maxPacketsPerSecond,
        final int connectTimeoutMs
    ) {
        this.bindHost = bindHost;
        this.bindPort = bindPort;
        this.backendHost = backendHost;
        this.backendPort = backendPort;
        this.bossThreads = bossThreads;
        this.workerThreads = workerThreads;
        this.maxBytesPerSecond = maxBytesPerSecond;
        this.maxPacketsPerSecond = maxPacketsPerSecond;
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public static VectorConfig defaults() {
        final int available = Runtime.getRuntime().availableProcessors();
        final int workerDefault = Math.max(1, available - 1);
        final int bindPort = envPortOrDefault(25565);
        return new VectorConfig(
            "0.0.0.0",
            bindPort,
            "127.0.0.1",
            25577,
            1,
            workerDefault,
            2_097_152L,
            4000L,
            3000
        );
    }

    private static int envPortOrDefault(final int fallback) {
        final String value = System.getenv("SERVER_PORT");
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public String bindHost() {
        return bindHost;
    }

    public int bindPort() {
        return bindPort;
    }

    public String backendHost() {
        return backendHost;
    }

    public int backendPort() {
        return backendPort;
    }

    public int bossThreads() {
        return bossThreads;
    }

    public int workerThreads() {
        return workerThreads;
    }

    public long maxBytesPerSecond() {
        return maxBytesPerSecond;
    }

    public long maxPacketsPerSecond() {
        return maxPacketsPerSecond;
    }

    public int connectTimeoutMs() {
        return connectTimeoutMs;
    }
}

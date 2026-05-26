package gg.literal.config;

import gg.literal.bootstrap.BootstrapException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VectorConfigLoader {

    private static final Pattern STRING_PATTERN = Pattern.compile("\"([a-z\\-]+)\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\"([a-z\\-]+)\"\\s*:\\s*([0-9]+)");

    private VectorConfigLoader() {
    }

    public static VectorConfig loadOrCreate(final Path path) throws BootstrapException {
        final VectorConfig defaults = VectorConfig.defaults();
        if (Files.notExists(path)) {
            writeDefaults(path, defaults);
            return defaults;
        }

        final String content;
        try {
            content = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new BootstrapException("Unable to read config.json", ex);
        }

        String bindHost = defaults.bindHost();
        int bindPort = defaults.bindPort();
        String backendHost = defaults.backendHost();
        int backendPort = defaults.backendPort();
        int bossThreads = defaults.bossThreads();
        int workerThreads = defaults.workerThreads();
        long maxBytesPerSecond = defaults.maxBytesPerSecond();
        long maxPacketsPerSecond = defaults.maxPacketsPerSecond();
        int connectTimeoutMs = defaults.connectTimeoutMs();

        final Matcher stringMatcher = STRING_PATTERN.matcher(content);
        while (stringMatcher.find()) {
            final String key = stringMatcher.group(1);
            final String value = stringMatcher.group(2);
            if ("bind-host".equals(key)) {
                bindHost = value;
            } else if ("backend-host".equals(key)) {
                backendHost = value;
            }
        }

        final Matcher numberMatcher = NUMBER_PATTERN.matcher(content);
        while (numberMatcher.find()) {
            final String key = numberMatcher.group(1);
            final long value = Long.parseLong(numberMatcher.group(2));
            if ("bind-port".equals(key)) {
                bindPort = (int) value;
            } else if ("backend-port".equals(key)) {
                backendPort = (int) value;
            } else if ("boss-threads".equals(key)) {
                bossThreads = (int) value;
            } else if ("worker-threads".equals(key)) {
                workerThreads = (int) value;
            } else if ("max-bytes-per-second".equals(key)) {
                maxBytesPerSecond = value;
            } else if ("max-packets-per-second".equals(key)) {
                maxPacketsPerSecond = value;
            } else if ("connect-timeout-ms".equals(key)) {
                connectTimeoutMs = (int) value;
            }
        }

        return new VectorConfig(
            bindHost,
            bindPort,
            backendHost,
            backendPort,
            bossThreads,
            workerThreads,
            maxBytesPerSecond,
            maxPacketsPerSecond,
            connectTimeoutMs
        );
    }

    private static void writeDefaults(final Path path, final VectorConfig defaults) throws BootstrapException {
        final String content = "{\n"
            + "  \"bind-host\": \"" + defaults.bindHost() + "\",\n"
            + "  \"bind-port\": " + defaults.bindPort() + ",\n"
            + "  \"backend-host\": \"" + defaults.backendHost() + "\",\n"
            + "  \"backend-port\": " + defaults.backendPort() + ",\n"
            + "  \"boss-threads\": " + defaults.bossThreads() + ",\n"
            + "  \"worker-threads\": " + defaults.workerThreads() + ",\n"
            + "  \"max-bytes-per-second\": " + defaults.maxBytesPerSecond() + ",\n"
            + "  \"max-packets-per-second\": " + defaults.maxPacketsPerSecond() + ",\n"
            + "  \"connect-timeout-ms\": " + defaults.connectTimeoutMs() + "\n"
            + "}\n";
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new BootstrapException("Unable to create config.json", ex);
        }
    }
}

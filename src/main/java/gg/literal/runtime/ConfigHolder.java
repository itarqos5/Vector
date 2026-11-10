package gg.literal.runtime;

import gg.literal.bootstrap.BootstrapException;
import gg.literal.config.VectorConfig;
import gg.literal.config.VectorConfigLoader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Holds the live configuration and supports hot reloading it from disk. */
public final class ConfigHolder {

    private final Path path;
    private final AtomicReference<VectorConfig> current;
    private final List<Consumer<VectorConfig>> listeners = new CopyOnWriteArrayList<>();

    public ConfigHolder(final Path path, final VectorConfig initial) {
        this.path = path;
        this.current = new AtomicReference<>(initial);
    }

    public VectorConfig get() {
        return current.get();
    }

    /** Registers a callback invoked (on the reloading thread) after every successful reload. */
    public void onReload(final Consumer<VectorConfig> listener) {
        listeners.add(listener);
    }

    /**
     * Re-reads the config file. On any error the previous configuration stays active and the
     * exception is rethrown.
     *
     * @return names of changed settings that only take effect after a restart
     */
    public synchronized List<String> reload() throws BootstrapException {
        final VectorConfig previous = current.get();
        final VectorConfig next = VectorConfigLoader.load(path);
        current.set(next);

        final List<String> restartRequired = new ArrayList<>();
        check(restartRequired, "bind-host", previous.bindHost(), next.bindHost());
        check(restartRequired, "bind-port", previous.bindPort(), next.bindPort());
        check(restartRequired, "boss-threads", previous.bossThreads(), next.bossThreads());
        check(restartRequired, "worker-threads", previous.workerThreads(), next.workerThreads());
        check(restartRequired, "metrics-enabled", previous.metrics().enabled(), next.metrics().enabled());
        check(restartRequired, "metrics-host", previous.metrics().host(), next.metrics().host());
        check(restartRequired, "metrics-port", previous.metrics().port(), next.metrics().port());

        for (final Consumer<VectorConfig> listener : listeners) {
            listener.accept(next);
        }
        return restartRequired;
    }

    private static void check(final List<String> out, final String key, final Object before, final Object after) {
        if (!Objects.equals(before, after)) {
            out.add(key);
        }
    }
}

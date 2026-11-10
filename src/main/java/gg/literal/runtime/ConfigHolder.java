package gg.literal.runtime;

import gg.literal.bootstrap.BootstrapException;
import gg.literal.config.Json;
import gg.literal.config.VectorConfig;
import gg.literal.config.VectorConfigLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        check(restartRequired, "dashboard-enabled", previous.dashboard().enabled(), next.dashboard().enabled());
        check(restartRequired, "dashboard-host", previous.dashboard().host(), next.dashboard().host());
        check(restartRequired, "dashboard-port", previous.dashboard().port(), next.dashboard().port());

        for (final Consumer<VectorConfig> listener : listeners) {
            listener.accept(next);
        }
        return restartRequired;
    }

    /**
     * Changes some top-level keys of the config file and reloads. The edited document is validated in a
     * temporary file first, so an invalid change never touches the real config.json; the file is then replaced
     * atomically. Note that rewriting normalises the formatting of the file.
     *
     * @return settings that changed but need a restart (see {@link #reload()})
     */
    public synchronized List<String> update(final Map<String, Object> changes) throws BootstrapException {
        final Map<String, Object> root;
        try {
            root = Files.exists(path)
                ? Json.parseObject(Files.readString(path, StandardCharsets.UTF_8))
                : new LinkedHashMap<>();
        } catch (IOException | IllegalArgumentException ex) {
            throw new BootstrapException("Cannot edit " + path.getFileName() + ": " + ex.getMessage(), ex);
        }
        root.putAll(changes);

        final Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.writeString(temp, Json.writePretty(root), StandardCharsets.UTF_8);
            VectorConfigLoader.load(temp); // throws if the edited document is invalid
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new BootstrapException("Cannot write " + path.getFileName() + ": " + ex.getMessage(), ex);
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // leftover temp file is harmless
            }
        }
        return reload();
    }

    private static void check(final List<String> out, final String key, final Object before, final Object after) {
        if (!Objects.equals(before, after)) {
            out.add(key);
        }
    }
}

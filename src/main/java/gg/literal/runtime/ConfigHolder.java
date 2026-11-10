package gg.literal.runtime;

import gg.literal.config.VectorConfig;

import java.util.concurrent.atomic.AtomicReference;

/** Holds the live configuration so components always read the current snapshot. */
public final class ConfigHolder {

    private final AtomicReference<VectorConfig> current;

    public ConfigHolder(final VectorConfig initial) {
        this.current = new AtomicReference<>(initial);
    }

    public VectorConfig get() {
        return current.get();
    }
}

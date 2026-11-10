package gg.literal.runtime;

/** Everything the pipeline handlers and console share. */
public final class VectorContext {

    public final ConfigHolder config;
    public final ConnectionRegistry registry;
    public final BanList banList;
    public final BackendPool pool;

    VectorContext(
        final ConfigHolder config,
        final ConnectionRegistry registry,
        final BanList banList,
        final BackendPool pool
    ) {
        this.config = config;
        this.registry = registry;
        this.banList = banList;
        this.pool = pool;
    }
}

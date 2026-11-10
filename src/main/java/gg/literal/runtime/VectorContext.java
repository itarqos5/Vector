package gg.literal.runtime;

/** Everything the pipeline handlers and console share. */
public final class VectorContext {

    public final ConfigHolder config;
    public final ConnectionRegistry registry;
    public final BanList banList;
    public final Whitelist whitelist;
    public final BackendPool pool;
    public final ConnectionLimiter limiter;

    VectorContext(
        final ConfigHolder config,
        final ConnectionRegistry registry,
        final BanList banList,
        final Whitelist whitelist,
        final BackendPool pool,
        final ConnectionLimiter limiter
    ) {
        this.config = config;
        this.registry = registry;
        this.banList = banList;
        this.whitelist = whitelist;
        this.pool = pool;
        this.limiter = limiter;
    }
}

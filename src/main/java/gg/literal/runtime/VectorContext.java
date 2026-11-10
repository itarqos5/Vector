package gg.literal.runtime;

import gg.literal.bootstrap.BootstrapException;

import java.util.List;

/** Everything the pipeline handlers, console and metrics endpoint share. */
public final class VectorContext {

    public final ConfigHolder config;
    public final ConnectionRegistry registry;
    public final BanList banList;
    public final Whitelist whitelist;
    public final BackendPool pool;
    public final ConnectionLimiter limiter;
    public final StatusService status;

    VectorContext(
        final ConfigHolder config,
        final ConnectionRegistry registry,
        final BanList banList,
        final Whitelist whitelist,
        final BackendPool pool,
        final ConnectionLimiter limiter,
        final StatusService status
    ) {
        this.config = config;
        this.registry = registry;
        this.banList = banList;
        this.whitelist = whitelist;
        this.pool = pool;
        this.limiter = limiter;
        this.status = status;
    }

    /**
     * Reloads {@code config.json} and the whitelist file. The backend pool is updated through the config
     * listener registered at boot.
     *
     * @return settings that changed but need a restart
     */
    public List<String> reload() throws BootstrapException {
        final List<String> restartRequired = config.reload();
        whitelist.load();
        return restartRequired;
    }
}

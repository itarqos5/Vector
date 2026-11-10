package gg.literal.runtime;

import gg.literal.config.VectorConfig;
import gg.literal.log.TerminalLogger;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns repeated abuse into a temporary ban. Handlers report each violation (a rate-limit hit, a packet flood,
 * a refused host name); once an IP accumulates {@code auto-ban-violations} of them within
 * {@code auto-ban-window-seconds} it is banned for {@code auto-ban-minutes} and its open connections are closed.
 */
public final class AutoBan {

    private static final class Window {
        long start;
        int count;
    }

    private final ConfigHolder config;
    private final BanList banList;
    private final ConnectionRegistry registry;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public AutoBan(final ConfigHolder config, final BanList banList, final ConnectionRegistry registry) {
        this.config = config;
        this.banList = banList;
        this.registry = registry;
    }

    /**
     * Records one violation by {@code ip}.
     *
     * @return true if this violation tripped the threshold and the IP was banned
     */
    public boolean violation(final String ip, final String reason) {
        final VectorConfig.Policy policy = config.get().policy();
        if (policy.autoBanViolations() <= 0 || banList.isBanned(ip)) {
            return false;
        }
        if (!tripped(ip, policy.autoBanViolations(), policy.autoBanWindowSeconds() * 1000L, System.currentTimeMillis())) {
            return false;
        }
        final long duration = policy.autoBanMinutes() * 60_000L;
        banList.ban(ip, duration, "auto-ban: " + reason);
        Metrics.INSTANCE.autoBans.increment();
        ActivityLog.INSTANCE.record("AUTO_BAN", ip, reason);
        final int kicked = registry.kickByIp(ip);
        TerminalLogger.warn("[AUTOBAN] " + ip + " banned for " + policy.autoBanMinutes() + " minutes after "
            + policy.autoBanViolations() + " violations (" + reason + ")" + (kicked > 0 ? ", closed " + kicked + " connection(s)" : ""));
        return true;
    }

    /** Counts a violation in the sliding window and reports whether the threshold is reached (resetting it). */
    boolean tripped(final String ip, final int threshold, final long windowMs, final long now) {
        final boolean[] result = {false};
        windows.compute(ip, (key, window) -> {
            final Window w = window == null ? new Window() : window;
            if (now - w.start >= windowMs) {
                w.start = now;
                w.count = 0;
            }
            w.count++;
            if (w.count >= threshold) {
                result[0] = true;
                return null; // forget the IP: it is about to be banned
            }
            return w;
        });
        return result[0];
    }

    /** Drops windows that have expired so the map does not grow with every IP that ever misbehaved once. */
    public void sweep() {
        final long windowMs = config.get().policy().autoBanWindowSeconds() * 1000L;
        final long now = System.currentTimeMillis();
        windows.entrySet().removeIf(e -> now - e.getValue().start >= windowMs);
    }

    int trackedIps() {
        return windows.size();
    }
}

package gg.literal.runtime;

import gg.literal.config.VectorConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Maps the host name a player connected with to the set of backends allowed to serve it.
 *
 * <p>Lookup order: exact host, then the most specific wildcard ({@code *.example.com}, longest suffix first),
 * then the catch-all {@code *}. When nothing matches, {@link #lookup} returns {@code null}, meaning "no
 * restriction".
 */
final class RouteTable {

    static final RouteTable EMPTY = new RouteTable(Map.of(), List.of(), null);

    private record Wildcard(String suffix, Set<String> backends) {
    }

    private final Map<String, Set<String>> exact;
    private final List<Wildcard> wildcards;
    private final Set<String> catchAll;

    private RouteTable(final Map<String, Set<String>> exact, final List<Wildcard> wildcards, final Set<String> catchAll) {
        this.exact = exact;
        this.wildcards = wildcards;
        this.catchAll = catchAll;
    }

    static RouteTable of(final List<VectorConfig.Route> routes) {
        if (routes.isEmpty()) {
            return EMPTY;
        }
        final Map<String, Set<String>> exact = new HashMap<>();
        final List<Wildcard> wildcards = new ArrayList<>();
        Set<String> catchAll = null;
        for (final VectorConfig.Route route : routes) {
            final Set<String> names = new HashSet<>();
            for (final String name : route.backends()) {
                names.add(name.toLowerCase(Locale.ROOT));
            }
            final String host = route.host().toLowerCase(Locale.ROOT);
            if (host.equals("*")) {
                catchAll = Set.copyOf(names);
            } else if (host.startsWith("*.")) {
                wildcards.add(new Wildcard(host.substring(1), Set.copyOf(names)));
            } else {
                exact.put(host, Set.copyOf(names));
            }
        }
        wildcards.sort((a, b) -> Integer.compare(b.suffix().length(), a.suffix().length()));
        return new RouteTable(Map.copyOf(exact), List.copyOf(wildcards), catchAll);
    }

    /**
     * @param host a host name already passed through {@link HostNames#normalize}
     * @return lower-cased backend names allowed for that host, or {@code null} if no route applies
     */
    Set<String> lookup(final String host) {
        final Set<String> direct = exact.get(host);
        if (direct != null) {
            return direct;
        }
        for (final Wildcard wildcard : wildcards) {
            if (host.length() > wildcard.suffix().length() && host.endsWith(wildcard.suffix())) {
                return wildcard.backends();
            }
        }
        return catchAll;
    }

    boolean isEmpty() {
        return exact.isEmpty() && wildcards.isEmpty() && catchAll == null;
    }
}

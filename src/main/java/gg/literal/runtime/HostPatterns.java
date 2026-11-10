package gg.literal.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A set of host name patterns: exact names, wildcards ({@code *.example.com}, which match subdomains but not the
 * bare domain) and {@code *} for everything. An empty set matches nothing; callers treat "no list configured"
 * as "allow all" themselves.
 */
final class HostPatterns {

    static final HostPatterns NONE = new HostPatterns(Set.of(), List.of(), false);

    private final Set<String> exact;
    private final List<String> suffixes;
    private final boolean any;

    private HostPatterns(final Set<String> exact, final List<String> suffixes, final boolean any) {
        this.exact = exact;
        this.suffixes = suffixes;
        this.any = any;
    }

    static HostPatterns of(final List<String> patterns) {
        if (patterns.isEmpty()) {
            return NONE;
        }
        final Set<String> exact = new HashSet<>();
        final List<String> suffixes = new ArrayList<>();
        boolean any = false;
        for (final String raw : patterns) {
            final String pattern = HostNames.normalize(raw);
            if (pattern.equals("*")) {
                any = true;
            } else if (pattern.startsWith("*.")) {
                suffixes.add(pattern.substring(1));
            } else if (!pattern.isEmpty()) {
                exact.add(pattern);
            }
        }
        return new HostPatterns(Set.copyOf(exact), List.copyOf(suffixes), any);
    }

    /** @param host a host name already passed through {@link HostNames#normalize} */
    boolean matches(final String host) {
        if (any || exact.contains(host)) {
            return true;
        }
        for (final String suffix : suffixes) {
            if (host.length() > suffix.length() && host.endsWith(suffix.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    boolean isEmpty() {
        return !any && exact.isEmpty() && suffixes.isEmpty();
    }
}

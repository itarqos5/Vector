package gg.literal.runtime;

import java.util.Locale;

/** Helpers for the server address a client puts in its handshake. */
final class HostNames {

    private HostNames() {
    }

    /**
     * Normalises the handshake's server address for matching: drops Forge/FML markers (everything after a NUL
     * byte), a trailing root dot and any surrounding whitespace, and lower-cases the result.
     *
     * @return the cleaned host name, or an empty string if nothing usable remains
     */
    static String normalize(final String raw) {
        if (raw == null) {
            return "";
        }
        String host = raw;
        final int nul = host.indexOf('\0');
        if (nul >= 0) {
            host = host.substring(0, nul);
        }
        host = host.trim();
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return host.toLowerCase(Locale.ROOT);
    }
}

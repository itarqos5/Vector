package gg.literal.runtime;

import gg.literal.config.Json;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * A server list response that Vector makes up itself (offline, maintenance) instead of relaying from a backend.
 * The serialised packet is cached per client protocol number and rebuilt whenever the MOTD text changes.
 */
final class SyntheticStatus {

    /** Protocol numbers come from clients, so the cache is bounded. */
    private static final int CACHE_LIMIT = 64;

    private final String versionName;
    private final Supplier<String> motd;
    private final ConcurrentHashMap<Integer, byte[]> cache = new ConcurrentHashMap<>();
    private volatile String cachedMotd = "";

    SyntheticStatus(final String versionName, final Supplier<String> motd) {
        this.versionName = versionName;
        this.motd = motd;
    }

    /** The complete Status Response packet (length, id, JSON) for a client speaking {@code clientProtocol}. */
    byte[] packet(final int clientProtocol) {
        final String text = motd.get();
        if (!text.equals(cachedMotd)) {
            cache.clear();
            cachedMotd = text;
        }
        final byte[] cached = cache.get(clientProtocol);
        if (cached != null) {
            return cached;
        }
        final byte[] built = build(clientProtocol, text);
        if (cache.size() < CACHE_LIMIT) {
            cache.put(clientProtocol, built);
        }
        return built;
    }

    /** The JSON document inside the packet; exposed for tests. */
    String json(final int clientProtocol, final String text) {
        final Map<String, Object> version = new LinkedHashMap<>();
        version.put("name", versionName);
        version.put("protocol", (long) clientProtocol);
        final Map<String, Object> players = new LinkedHashMap<>();
        players.put("max", 0L);
        players.put("online", 0L);
        final Map<String, Object> description = new LinkedHashMap<>();
        description.put("text", text);
        final Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", version);
        root.put("players", players);
        root.put("description", description);
        return Json.write(root);
    }

    private byte[] build(final int clientProtocol, final String text) {
        return McProtocol.stringPacketBytes(McProtocol.PACKET_STATUS_RESPONSE, json(clientProtocol, text));
    }
}

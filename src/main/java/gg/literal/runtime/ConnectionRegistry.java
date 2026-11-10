package gg.literal.runtime;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.util.AttributeKey;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public final class ConnectionRegistry {

    /** Epoch millis at which the client connected. */
    public static final AttributeKey<Long> ATTR_CONNECTED_AT = AttributeKey.newInstance("vec-connected-at");

    /** Bytes relayed for one connection. Written by the event loop, read by the dashboard. */
    public static final class Traffic {
        public final java.util.concurrent.atomic.AtomicLong fromClient = new java.util.concurrent.atomic.AtomicLong();
        public final java.util.concurrent.atomic.AtomicLong toClient = new java.util.concurrent.atomic.AtomicLong();
    }

    public static final AttributeKey<Traffic> ATTR_TRAFFIC = AttributeKey.newInstance("vec-traffic");

    /** Read-only view of one open client connection. */
    public record ConnectionInfo(
        String ip,
        int port,
        String player,
        String backend,
        long connectedAtMillis,
        Integer protocol,
        long bytesFromClient,
        long bytesToClient
    ) {
    }

    /**
     * Key: "ip:port" (full socket address) — unique per connection.
     * Value: the inbound client channel.
     */
    private final ConcurrentHashMap<String, Channel> activeChannels = new ConcurrentHashMap<>();

    /** Index: client IP -> address keys of its open connections, so per-IP actions do not scan everything. */
    private final ConcurrentHashMap<String, Set<String>> keysByIp = new ConcurrentHashMap<>();

    /**
     * Key: plain IP. One-shot override applied when a player with this IP
     * makes their next inbound connection.
     */
    private final ConcurrentHashMap<String, InetSocketAddress> nextBackendOverrides = new ConcurrentHashMap<>();

    /**
     * Sends all active connections from {@code ip} to a new backend via the Minecraft
     * Transfer packet (S\u2192C, Play state). Falls back to a plain kick+override for
     * old-protocol or undetected-state connections. The backend override is always set
     * so a manual reconnect also lands on the correct server.
     */
    public void sendToServer(final String ip, final String destHost, final int destPort) {
        setNextBackend(ip, new InetSocketAddress(destHost, destPort)); // fallback for manual reconnect
        for (final Channel ch : channelsOf(ip)) {
            if (!ch.isActive()) continue;

            final Integer protoVer = ch.attr(MinecraftProtocolSniffer.ATTR_PROTOCOL_VERSION).get();
            final Boolean compressed = ch.attr(MinecraftProtocolSniffer.ATTR_COMPRESSION).get();

            // Transfer packet was introduced in 1.20.5 (protocol 763).
            if (protoVer != null && protoVer >= 763) {
                final int packetId = resolveTransferPacketId(protoVer);
                final ByteBuf pkt = buildTransferPacket(destHost, destPort, packetId,
                        Boolean.TRUE.equals(compressed));
                ch.writeAndFlush(pkt).addListener(ChannelFutureListener.CLOSE);
            } else {
                // Unknown / old protocol — override already set, just kick.
                ch.close();
            }
        }
    }

    /**
     * Returns the S\u2192C Transfer packet ID for the given protocol version.
     * Only called for protocol >= 763 (1.20.5), where Transfer was introduced.
     * Uses 0x7F as the best-known value for modern (1.21.x) clients.
     */
    private static int resolveTransferPacketId(final int protocol) {
        // 0x7F verified for protocol 773 (1.21.10).
        // The ID is stable across 1.21.x — use it for all modern versions.
        return 0x7F;
    }

    /**
     * Builds a raw Minecraft Transfer packet (S\u2192C, Play) with the correct framing.
     *
     * @param host       destination hostname/IP
     * @param port       destination port
     * @param packetId   numeric packet ID (e.g. 0x7F)
     * @param compressed whether the connection has compression enabled (Paper default: true)
     */
    private static ByteBuf buildTransferPacket(
            final String host, final int port, final int packetId, final boolean compressed) {
        // Build payload: [VarInt packetId][VarInt hostLen][host bytes][VarInt port]
        final byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
        final ByteBuf payload = Unpooled.buffer(4 + hostBytes.length + 4);
        writeVarInt(payload, packetId);
        writeVarInt(payload, hostBytes.length);
        payload.writeBytes(hostBytes);
        writeVarInt(payload, port);

        // Wrap with Minecraft framing
        final ByteBuf framed = Unpooled.buffer(payload.readableBytes() + 8);
        if (compressed) {
            // Compression format: [length = 1 + payload][data_length = 0 (not compressed)][payload]
            writeVarInt(framed, 1 + payload.readableBytes());
            writeVarInt(framed, 0); // data_length = 0 means "uncompressed"
        } else {
            writeVarInt(framed, payload.readableBytes());
        }
        framed.writeBytes(payload);
        payload.release();
        return framed;
    }

    private static void writeVarInt(final ByteBuf buf, int value) {
        while (true) {
            if ((value & ~0x7F) == 0) { buf.writeByte(value); return; }
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    // -------------------------------------------------------------------------
    // Existing methods below
    // -------------------------------------------------------------------------

    public void register(final String addressKey, final Channel channel) {
        channel.attr(ATTR_CONNECTED_AT).set(System.currentTimeMillis());
        channel.attr(ATTR_TRAFFIC).set(new Traffic());
        activeChannels.put(addressKey, channel);
        Metrics.INSTANCE.observeConnections(activeChannels.size());
        keysByIp.compute(ipOfKey(addressKey), (ip, keys) -> {
            final Set<String> set = keys == null ? ConcurrentHashMap.newKeySet() : keys;
            set.add(addressKey);
            return set;
        });
    }

    public void unregister(final String addressKey) {
        activeChannels.remove(addressKey);
        keysByIp.computeIfPresent(ipOfKey(addressKey), (ip, keys) -> {
            keys.remove(addressKey);
            return keys.isEmpty() ? null : keys;
        });
    }

    /**
     * Closes all active channels whose address key starts with {@code ip + ":"}.
     * Returns the number of channels closed.
     */
    public int kickByIp(final String ip) {
        int count = 0;
        for (final Channel ch : channelsOf(ip)) {
            if (ch.isActive()) {
                ch.close();
                count++;
            }
        }
        return count;
    }

    /** Open channels of one client IP, found through the per-IP index instead of scanning every connection. */
    private List<Channel> channelsOf(final String ip) {
        final Set<String> keys = keysByIp.get(ip);
        if (keys == null) {
            return List.of();
        }
        final List<Channel> out = new ArrayList<>(keys.size());
        for (final String key : keys) {
            final Channel channel = activeChannels.get(key);
            if (channel != null) {
                out.add(channel);
            }
        }
        return out;
    }

    private static String ipOfKey(final String key) {
        final int colon = key.lastIndexOf(':');
        return colon > 0 ? key.substring(0, colon) : key;
    }

    /**
     * Retrieves and removes the next-backend override for a given IP, if any.
     */
    public InetSocketAddress pollNextBackend(final String ip) {
        return nextBackendOverrides.remove(ip);
    }

    /**
     * Sets a one-shot backend override for the next connection from {@code ip}.
     */
    public void setNextBackend(final String ip, final InetSocketAddress address) {
        nextBackendOverrides.put(ip, address);
    }

    /**
     * Returns a snapshot list of "ip:port -> connected/inactive" entries for display.
     */
    public List<String> listConnections() {
        final List<String> out = new ArrayList<>();
        for (final Map.Entry<String, Channel> entry : activeChannels.entrySet()) {
            final Channel channel = entry.getValue();
            final StringBuilder line = new StringBuilder(entry.getKey());
            final String player = channel.attr(MinecraftProtocolSniffer.ATTR_PLAYER_NAME).get();
            if (player != null) {
                line.append(" (").append(player).append(')');
            }
            final String backend = channel.attr(EuFrontendHandler.ATTR_BACKEND).get();
            if (backend != null) {
                line.append(" -> ").append(backend);
            }
            line.append(" [").append(channel.isActive() ? "active" : "closed").append(']');
            out.add(line.toString());
        }
        return out;
    }

    /**
     * Routes the next connection from {@code ip} to {@code backend} and disconnects the player's current
     * sessions so they reconnect straight onto it.
     *
     * @return number of sessions closed
     */
    public int sendToBackend(final String ip, final Backend backend) {
        setNextBackend(ip, new InetSocketAddress(backend.host(), backend.port()));
        return kickByIp(ip);
    }

    /** Closes every connection currently routed to the named backend (case-insensitive); returns how many. */
    public int kickBackend(final String backendName) {
        int count = 0;
        for (final Channel channel : activeChannels.values()) {
            final String backend = channel.attr(EuFrontendHandler.ATTR_BACKEND).get();
            if (channel.isActive() && backend != null && backend.equalsIgnoreCase(backendName)) {
                channel.close();
                count++;
            }
        }
        return count;
    }

    /**
     * Disconnects everyone routed to {@code fromName} and sets a one-shot override so each of those IPs lands
     * on {@code to} when they reconnect.
     *
     * @return number of connections closed
     */
    public int evacuate(final String fromName, final Backend to) {
        final java.util.Set<String> ips = new java.util.LinkedHashSet<>();
        int count = 0;
        for (final Map.Entry<String, Channel> entry : activeChannels.entrySet()) {
            final Channel channel = entry.getValue();
            final String backend = channel.attr(EuFrontendHandler.ATTR_BACKEND).get();
            if (channel.isActive() && backend != null && backend.equalsIgnoreCase(fromName)) {
                ips.add(ipOfKey(entry.getKey()));
                channel.close();
                count++;
            }
        }
        for (final String ip : ips) {
            setNextBackend(ip, new InetSocketAddress(to.host(), to.port()));
        }
        return count;
    }

    /**
     * Closes every active connection whose client IP satisfies {@code matcher} (used for CIDR bans).
     * Returns the number of channels closed.
     */
    public int kickIf(final Predicate<String> matcher) {
        int count = 0;
        for (final Map.Entry<String, Channel> entry : activeChannels.entrySet()) {
            final String key = entry.getKey();
            final int colon = key.lastIndexOf(':');
            if (colon > 0 && matcher.test(key.substring(0, colon)) && entry.getValue().isActive()) {
                entry.getValue().close();
                count++;
            }
        }
        return count;
    }

    /** Snapshot of every open connection, newest first. */
    public List<ConnectionInfo> snapshot() {
        final List<ConnectionInfo> out = new ArrayList<>();
        for (final Map.Entry<String, Channel> entry : activeChannels.entrySet()) {
            final String key = entry.getKey();
            final Channel channel = entry.getValue();
            final int colon = key.lastIndexOf(':');
            int port = 0;
            if (colon > 0) {
                try {
                    port = Integer.parseInt(key.substring(colon + 1));
                } catch (NumberFormatException ignored) {
                    // keep 0
                }
            }
            final Long since = channel.attr(ATTR_CONNECTED_AT).get();
            final Traffic traffic = channel.attr(ATTR_TRAFFIC).get();
            out.add(new ConnectionInfo(
                colon > 0 ? key.substring(0, colon) : key,
                port,
                channel.attr(MinecraftProtocolSniffer.ATTR_PLAYER_NAME).get(),
                channel.attr(EuFrontendHandler.ATTR_BACKEND).get(),
                since == null ? 0L : since,
                channel.attr(MinecraftProtocolSniffer.ATTR_PROTOCOL_VERSION).get(),
                traffic == null ? 0L : traffic.fromClient.get(),
                traffic == null ? 0L : traffic.toClient.get()
            ));
        }
        out.sort((a, b) -> Long.compare(b.connectedAtMillis(), a.connectedAtMillis()));
        return out;
    }

    public int size() {
        return activeChannels.size();
    }

    public static String toAddressKey(final Channel channel) {
        if (channel.remoteAddress() instanceof InetSocketAddress addr) {
            return addr.getAddress().getHostAddress() + ":" + addr.getPort();
        }
        return String.valueOf(channel.remoteAddress());
    }

    public static String toIp(final Channel channel) {
        if (channel.remoteAddress() instanceof InetSocketAddress addr) {
            return addr.getAddress().getHostAddress();
        }
        return String.valueOf(channel.remoteAddress());
    }
}

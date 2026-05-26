package gg.literal.runtime;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ConnectionRegistry {

    /**
     * Key: "ip:port" (full socket address) — unique per connection.
     * Value: the inbound client channel.
     */
    private final ConcurrentHashMap<String, Channel> activeChannels = new ConcurrentHashMap<>();

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
        for (final Map.Entry<String, Channel> entry : activeChannels.entrySet()) {
            if (!entry.getKey().startsWith(ip + ":")) continue;
            final Channel ch = entry.getValue();
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
        activeChannels.put(addressKey, channel);
    }

    public void unregister(final String addressKey) {
        activeChannels.remove(addressKey);
    }

    /**
     * Closes all active channels whose address key starts with {@code ip + ":"}.
     * Returns the number of channels closed.
     */
    public int kickByIp(final String ip) {
        int count = 0;
        for (final Map.Entry<String, Channel> entry : activeChannels.entrySet()) {
            if (entry.getKey().startsWith(ip + ":")) {
                final Channel ch = entry.getValue();
                if (ch.isActive()) {
                    ch.close();
                    count++;
                }
            }
        }
        return count;
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
            out.add(entry.getKey() + " [" + (entry.getValue().isActive() ? "active" : "closed") + "]");
        }
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

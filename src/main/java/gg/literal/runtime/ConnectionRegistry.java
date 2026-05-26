package gg.literal.runtime;

import io.netty.channel.Channel;

import java.net.InetSocketAddress;
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

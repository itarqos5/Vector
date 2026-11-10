package gg.literal.runtime;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/** An IPv4/IPv6 network in CIDR notation, plus literal-only IP parsing (never performs DNS). */
final class Cidr {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9a-fA-F:.]+");

    private final byte[] network;
    private final int prefix;
    private final String text;

    private Cidr(final byte[] network, final int prefix, final String text) {
        this.network = network;
        this.prefix = prefix;
        this.text = text;
    }

    /**
     * Parses {@code a.b.c.d/nn} or an IPv6 equivalent. Returns {@code null} if {@code token} is not a
     * valid CIDR block. A bare IP is accepted and treated as a single-host network.
     */
    static Cidr parse(final String token) {
        final int slash = token.indexOf('/');
        final String ipPart = slash < 0 ? token : token.substring(0, slash);
        final byte[] address = parseLiteral(ipPart);
        if (address == null) {
            return null;
        }
        int prefix = address.length * 8;
        if (slash >= 0) {
            try {
                prefix = Integer.parseInt(token.substring(slash + 1));
            } catch (NumberFormatException ex) {
                return null;
            }
            if (prefix < 0 || prefix > address.length * 8) {
                return null;
            }
        }
        // Zero the host bits so "10.1.2.3/8" and "10.0.0.0/8" are the same network.
        final byte[] masked = address.clone();
        for (int bit = prefix; bit < masked.length * 8; bit++) {
            masked[bit / 8] &= (byte) ~(0x80 >> (bit % 8));
        }
        final String canonical = prefix == address.length * 8 && slash < 0
            ? hostText(address)
            : hostText(masked) + "/" + prefix;
        return new Cidr(masked, prefix, canonical);
    }

    /** Returns the canonical text of a literal IP address, or {@code null} if it isn't one. */
    static String normalizeIp(final String ip) {
        final byte[] bytes = parseLiteral(ip);
        return bytes == null ? null : hostText(bytes);
    }

    boolean isSingleHost() {
        return prefix == network.length * 8;
    }

    /** True if the literal IP {@code ip} lies inside this network. */
    boolean contains(final String ip) {
        return contains(parseLiteral(ip));
    }

    /** Parses a literal IP into its address bytes once, for repeated {@link #contains(byte[])} calls. */
    static byte[] addressBytes(final String ip) {
        return parseLiteral(ip);
    }

    boolean contains(final byte[] address) {
        if (address == null || address.length != network.length) {
            return false;
        }
        final int fullBytes = prefix / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (address[i] != network[i]) {
                return false;
            }
        }
        final int remaining = prefix % 8;
        if (remaining == 0) {
            return true;
        }
        final int mask = (0xFF << (8 - remaining)) & 0xFF;
        return (address[fullBytes] & mask) == (network[fullBytes] & 0xFF);
    }

    @Override
    public String toString() {
        return text;
    }

    private static byte[] parseLiteral(final String ip) {
        if (ip == null || ip.isEmpty()) {
            return null;
        }
        final boolean v4 = IPV4.matcher(ip).matches();
        if (v4) {
            // An out-of-range octet would make InetAddress fall back to a DNS lookup, so reject it here.
            for (final String octet : ip.split("\\.")) {
                if (Integer.parseInt(octet) > 255) {
                    return null;
                }
            }
        } else if (ip.indexOf(':') < 0 || !IPV6.matcher(ip).matches()) {
            return null;
        }
        try {
            // Literal addresses are parsed locally; no name lookup happens for these shapes.
            return InetAddress.getByName(ip).getAddress();
        } catch (UnknownHostException ex) {
            return null;
        }
    }

    private static String hostText(final byte[] address) {
        try {
            return InetAddress.getByAddress(address).getHostAddress();
        } catch (UnknownHostException ex) {
            throw new IllegalStateException(ex);
        }
    }
}

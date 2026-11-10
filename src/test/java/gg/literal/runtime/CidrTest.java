package gg.literal.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CidrTest {

    @Test
    void zeroesHostBitsWhenParsing() {
        assertEquals("10.0.0.0/8", Cidr.parse("10.1.2.3/8").toString());
        assertEquals("192.168.1.128/25", Cidr.parse("192.168.1.200/25").toString());
    }

    @Test
    void bareAddressIsASingleHost() {
        final Cidr single = Cidr.parse("203.0.113.9");
        assertTrue(single.isSingleHost());
        assertEquals("203.0.113.9", single.toString());
        assertTrue(single.contains("203.0.113.9"));
        assertFalse(single.contains("203.0.113.10"));
    }

    @Test
    void matchesAtPrefixBoundaries() {
        final Cidr net = Cidr.parse("192.168.1.128/25");
        assertTrue(net.contains("192.168.1.128"));
        assertTrue(net.contains("192.168.1.255"));
        assertFalse(net.contains("192.168.1.127"));
        assertFalse(net.contains("192.168.2.130"));
    }

    @Test
    void zeroPrefixMatchesEveryAddressOfThatFamily() {
        final Cidr all = Cidr.parse("0.0.0.0/0");
        assertTrue(all.contains("8.8.8.8"));
        assertFalse(all.contains("2001:db8::1"));
    }

    @Test
    void supportsIpv6() {
        final Cidr net = Cidr.parse("2001:db8::/32");
        assertTrue(net.contains("2001:db8:ffff::1"));
        assertFalse(net.contains("2001:db9::1"));
        assertFalse(net.contains("10.0.0.1"));
    }

    @Test
    void rejectsInvalidInput() {
        assertNull(Cidr.parse("999.1.1.1"));
        assertNull(Cidr.parse("1.2.3"));
        assertNull(Cidr.parse("example.com"));
        assertNull(Cidr.parse("1.2.3.4/33"));
        assertNull(Cidr.parse("::1/129"));
        assertNull(Cidr.parse("1.2.3.4/x"));
        assertNull(Cidr.parse(""));
    }

    @Test
    void normalizesLiteralAddresses() {
        assertEquals("127.0.0.1", Cidr.normalizeIp("127.0.0.1"));
        assertNotNull(Cidr.normalizeIp("::1"));
        assertNull(Cidr.normalizeIp("localhost"));
    }
}

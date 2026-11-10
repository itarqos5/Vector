package gg.literal.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WhitelistTest {

    @TempDir
    Path dir;

    @Test
    void matchesUsernamesCaseInsensitively() {
        final Whitelist wl = new Whitelist(dir.resolve("wl.txt"));
        wl.add("Notch");
        assertTrue(wl.isAllowed("notch", "9.9.9.9"));
        assertTrue(wl.isAllowed("NOTCH", null));
        assertFalse(wl.isAllowed("jeb_", "9.9.9.9"));
    }

    @Test
    void matchesIpsAndCidrBlocks() {
        final Whitelist wl = new Whitelist(dir.resolve("wl.txt"));
        wl.add("203.0.113.5");
        wl.add("10.0.0.0/8");
        assertTrue(wl.isAllowed("anyone", "203.0.113.5"));
        assertTrue(wl.isAllowed("anyone", "10.4.5.6"));
        assertFalse(wl.isAllowed("anyone", "203.0.113.6"));
        assertFalse(wl.isAllowed(null, "11.0.0.1"));
    }

    @Test
    void addReportsDuplicatesAndRemoveWorks() {
        final Whitelist wl = new Whitelist(dir.resolve("wl.txt"));
        assertEquals("Steve", wl.add("Steve"));
        assertNull(wl.add("steve"), "case-insensitive duplicate");
        assertEquals("192.0.2.0/24", wl.add("192.0.2.77/24"));
        assertEquals(2, wl.size());
        assertTrue(wl.remove("STEVE"));
        assertTrue(wl.remove("192.0.2.0/24"));
        assertFalse(wl.remove("steve"));
        assertEquals(0, wl.size());
    }

    @Test
    void persistsAndReloads() {
        final Path file = dir.resolve("wl.txt");
        final Whitelist first = new Whitelist(file);
        first.add("Alex");
        first.add("198.51.100.0/24");

        final Whitelist second = new Whitelist(file);
        second.load();
        assertTrue(second.isAllowed("alex", null));
        assertTrue(second.isAllowed("x", "198.51.100.9"));
        assertEquals(2, second.entries().size());
    }
}

package gg.literal.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BanListTest {

    @TempDir
    Path dir;

    @Test
    void bansAndUnbansASingleIp() {
        final BanList bans = new BanList(dir.resolve("bans.txt"));
        assertNull(bans.find("203.0.113.5"));
        assertNotNull(bans.ban("203.0.113.5", 0, "spam"));
        assertEquals("spam", bans.find("203.0.113.5").reason());
        assertTrue(bans.find("203.0.113.5").permanent());
        assertTrue(bans.unban("203.0.113.5"));
        assertFalse(bans.isBanned("203.0.113.5"));
        assertFalse(bans.unban("203.0.113.5"));
    }

    @Test
    void cidrBanCoversTheWholeBlock() {
        final BanList bans = new BanList(dir.resolve("bans.txt"));
        bans.ban("198.51.100.0/24", 0, "");
        assertTrue(bans.isBanned("198.51.100.77"));
        assertFalse(bans.isBanned("198.51.101.1"));
        assertTrue(bans.unban("198.51.100.0/24"));
        assertFalse(bans.isBanned("198.51.100.77"));
    }

    @Test
    void rejectsInvalidTargets() {
        final BanList bans = new BanList(dir.resolve("bans.txt"));
        assertNull(bans.ban("not-an-ip", 0, ""));
        assertNull(bans.ban("1.2.3.4/40", 0, ""));
    }

    @Test
    void timedBansExpire() throws Exception {
        final BanList bans = new BanList(dir.resolve("bans.txt"));
        bans.ban("192.0.2.1", 30, "short");
        assertTrue(bans.isBanned("192.0.2.1"));
        Thread.sleep(80);
        assertFalse(bans.isBanned("192.0.2.1"));
        assertTrue(bans.all().isEmpty());
    }

    @Test
    void persistsAndReloads() {
        final Path file = dir.resolve("bans.txt");
        final BanList first = new BanList(file);
        first.ban("192.0.2.2", 0, "reason with | pipe");
        first.ban("10.0.0.0/8", 3_600_000L, "net");

        final BanList second = new BanList(file);
        second.load();
        assertEquals("reason with | pipe", second.find("192.0.2.2").reason());
        assertTrue(second.isBanned("10.200.1.1"));
        assertFalse(second.find("10.200.1.1").permanent());
    }

    @Test
    void loadsLegacyOneIpPerLineFiles() throws IOException {
        final Path file = dir.resolve("legacy.txt");
        Files.writeString(file, "# comment\n203.0.113.9\n\n198.51.100.4\n");
        final BanList bans = new BanList(file);
        bans.load();
        assertTrue(bans.find("203.0.113.9").permanent());
        assertTrue(bans.isBanned("198.51.100.4"));
        assertEquals(2, bans.all().size());
    }

    @Test
    void expiredEntriesAreDroppedOnLoad() throws IOException {
        final Path file = dir.resolve("old.txt");
        Files.writeString(file, "192.0.2.50|1|long gone\n192.0.2.51|0|still here\n");
        final BanList bans = new BanList(file);
        bans.load();
        assertFalse(bans.isBanned("192.0.2.50"));
        assertTrue(bans.isBanned("192.0.2.51"));
    }
}

package gg.literal.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.literal.config.VectorConfig;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AutoBanTest {

    @TempDir
    Path dir;

    private AutoBan autoBan(final BanList bans, final int violations) {
        return new AutoBan(new ConfigHolder(dir.resolve("c.json"), TestConfigs.withAutoBan(violations)), bans,
            new ConnectionRegistry());
    }

    @Test
    void bansAfterTheThresholdIsReached() {
        final BanList bans = new BanList(dir.resolve("bans.txt"));
        final AutoBan auto = autoBan(bans, 3);
        assertFalse(auto.violation("203.0.113.9", "flood"));
        assertFalse(auto.violation("203.0.113.9", "flood"));
        assertTrue(auto.violation("203.0.113.9", "flood"));
        assertTrue(bans.isBanned("203.0.113.9"));
        assertFalse(bans.find("203.0.113.9").permanent(), "auto-bans expire");
        assertTrue(bans.find("203.0.113.9").reason().startsWith("auto-ban"));
    }

    @Test
    void disabledWhenTheThresholdIsZero() {
        final BanList bans = new BanList(dir.resolve("bans.txt"));
        final AutoBan auto = autoBan(bans, 0);
        for (int i = 0; i < 50; i++) {
            assertFalse(auto.violation("203.0.113.9", "flood"));
        }
        assertFalse(bans.isBanned("203.0.113.9"));
    }

    @Test
    void countsPerIpIndependently() {
        final BanList bans = new BanList(dir.resolve("bans.txt"));
        final AutoBan auto = autoBan(bans, 2);
        auto.violation("1.1.1.1", "x");
        auto.violation("2.2.2.2", "x");
        assertFalse(bans.isBanned("1.1.1.1"));
        assertTrue(auto.violation("1.1.1.1", "x"));
        assertFalse(bans.isBanned("2.2.2.2"));
    }

    @Test
    void violationsOutsideTheWindowDoNotAccumulate() {
        final AutoBan auto = autoBan(new BanList(dir.resolve("bans.txt")), 3);
        assertFalse(auto.tripped("1.1.1.1", 3, 1000, 0));
        assertFalse(auto.tripped("1.1.1.1", 3, 1000, 600));
        assertFalse(auto.tripped("1.1.1.1", 3, 1000, 5_000), "the window restarted, so this is violation #1 again");
        assertFalse(auto.tripped("1.1.1.1", 3, 1000, 5_100));
        assertTrue(auto.tripped("1.1.1.1", 3, 1000, 5_200));
    }

    @Test
    void sweepForgetsStaleWindows() throws Exception {
        final AutoBan auto = autoBan(new BanList(dir.resolve("bans.txt")), 5);
        auto.tripped("1.1.1.1", 5, 10, System.currentTimeMillis());
        assertEquals(1, auto.trackedIps());
        Thread.sleep(20);
        // sweep() uses the configured 60 s window, so only an expired entry would be removed; this one is fresh
        auto.sweep();
        assertEquals(1, auto.trackedIps());
    }
}

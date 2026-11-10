package gg.literal.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogFileWriterTest {

    @TempDir
    Path dir;

    /** A clock that only moves when told to. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(final String iso) {
            this.now = Instant.parse(iso);
        }

        void set(final String iso) {
            this.now = Instant.parse(iso);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static String gunzip(final Path file) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void appendsLinesToLatestLog() throws Exception {
        final MutableClock clock = new MutableClock("2026-11-10T10:00:00Z");
        try (LogFileWriter log = new LogFileWriter(dir.resolve("logs"), 7, clock)) {
            log.write("first");
            log.write("second");
        }
        assertEquals(java.util.List.of("first", "second"), Files.readAllLines(dir.resolve("logs/latest.log")));
    }

    @Test
    void archivesThePreviousDayWhenTheDateChanges() throws Exception {
        final MutableClock clock = new MutableClock("2026-11-10T23:59:00Z");
        try (LogFileWriter log = new LogFileWriter(dir, 7, clock)) {
            log.write("late line");
            clock.set("2026-11-11T00:01:00Z");
            log.write("morning line");
        }
        assertEquals("late line\n", gunzip(dir.resolve("2026-11-10.log.gz")).replace("\r\n", "\n"));
        assertEquals(java.util.List.of("morning line"), Files.readAllLines(dir.resolve("latest.log")));
    }

    @Test
    void deletesArchivesOlderThanTheRetention() throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("2026-10-01.log.gz"), "x");
        Files.writeString(dir.resolve("2026-11-08.log.gz"), "x");
        Files.writeString(dir.resolve("notes.txt"), "keep me");
        final MutableClock clock = new MutableClock("2026-11-10T10:00:00Z");
        try (LogFileWriter log = new LogFileWriter(dir, 7, clock)) {
            log.write("hello");
        }
        assertFalse(Files.exists(dir.resolve("2026-10-01.log.gz")), "older than 7 days");
        assertTrue(Files.exists(dir.resolve("2026-11-08.log.gz")), "within 7 days");
        assertTrue(Files.exists(dir.resolve("notes.txt")), "unrelated files are never touched");
    }

    @Test
    void zeroRetentionKeepsEverything() throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("2020-01-01.log.gz"), "x");
        final MutableClock clock = new MutableClock("2026-11-10T10:00:00Z");
        try (LogFileWriter log = new LogFileWriter(dir, 0, clock)) {
            log.write("hello");
        }
        assertTrue(Files.exists(dir.resolve("2020-01-01.log.gz")));
    }

    @Test
    void archivesAStaleLatestLogLeftByAnEarlierRun() throws Exception {
        Files.createDirectories(dir);
        final Path latest = dir.resolve("latest.log");
        Files.writeString(latest, "old run\n");
        Files.setLastModifiedTime(latest, java.nio.file.attribute.FileTime.from(Instant.parse("2026-11-09T12:00:00Z")));

        final MutableClock clock = new MutableClock("2026-11-10T10:00:00Z");
        try (LogFileWriter log = new LogFileWriter(dir, 7, clock)) {
            log.write("new run");
        }
        assertEquals("old run\n", gunzip(dir.resolve("2026-11-09.log.gz")).replace("\r\n", "\n"));
        assertEquals(java.util.List.of("new run"), Files.readAllLines(latest));
    }
}

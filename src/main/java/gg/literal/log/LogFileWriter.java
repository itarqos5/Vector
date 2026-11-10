package gg.literal.log;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * Appends log lines to {@code <dir>/latest.log}. When the date changes the previous day's file is gzipped to
 * {@code yyyy-MM-dd.log.gz} and archives older than {@code keepDays} are deleted. Failures never propagate:
 * logging must not take the proxy down.
 */
public final class LogFileWriter implements AutoCloseable {

    private final Path dir;
    private final int keepDays;
    private final Clock clock;
    private BufferedWriter writer;
    private LocalDate currentDay;

    public LogFileWriter(final Path dir, final int keepDays) {
        this(dir, keepDays, Clock.systemDefaultZone());
    }

    LogFileWriter(final Path dir, final int keepDays, final Clock clock) {
        this.dir = dir;
        this.keepDays = keepDays;
        this.clock = clock;
    }

    public synchronized void write(final String line) {
        try {
            final LocalDate today = LocalDate.now(clock);
            if (writer == null) {
                open(today);
            } else if (!today.equals(currentDay)) {
                close();
                archive(currentDay);
                open(today);
                prune(today);
            }
            writer.write(line);
            writer.newLine();
            writer.flush();
        } catch (IOException ex) {
            // Best effort only; report once to stderr so the problem is visible.
            System.err.println("Vector: cannot write the log file: " + ex.getMessage());
        }
    }

    @Override
    public synchronized void close() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
                // nothing useful to do
            }
            writer = null;
        }
    }

    private void open(final LocalDate today) throws IOException {
        Files.createDirectories(dir);
        final Path latest = dir.resolve("latest.log");
        // A latest.log left over from an earlier day (e.g. after a restart) is archived first.
        if (Files.exists(latest) && Files.size(latest) > 0) {
            final LocalDate modified = LocalDate.ofInstant(Files.getLastModifiedTime(latest).toInstant(), clock.getZone());
            if (modified.isBefore(today)) {
                archive(modified);
            }
        }
        writer = Files.newBufferedWriter(latest, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        currentDay = today;
        prune(today);
    }

    /** Compresses latest.log into the archive for {@code day}, appending a counter if that name is taken. */
    private void archive(final LocalDate day) throws IOException {
        final Path latest = dir.resolve("latest.log");
        if (!Files.exists(latest) || Files.size(latest) == 0) {
            return;
        }
        Path target = dir.resolve(day + ".log.gz");
        for (int i = 2; Files.exists(target); i++) {
            target = dir.resolve(day + "-" + i + ".log.gz");
        }
        try (InputStream in = Files.newInputStream(latest);
             OutputStream out = new GZIPOutputStream(Files.newOutputStream(target))) {
            in.transferTo(out);
        }
        Files.delete(latest);
    }

    private void prune(final LocalDate today) throws IOException {
        if (keepDays <= 0) {
            return;
        }
        final LocalDate oldest = today.minusDays(keepDays);
        try (Stream<Path> files = Files.list(dir)) {
            final List<Path> stale = files.filter(p -> {
                final String name = p.getFileName().toString();
                if (!name.endsWith(".log.gz") || name.length() < 10) {
                    return false;
                }
                try {
                    return LocalDate.parse(name.substring(0, 10)).isBefore(oldest);
                } catch (RuntimeException notADate) {
                    return false;
                }
            }).toList();
            for (final Path path : stale) {
                Files.deleteIfExists(path);
            }
        }
    }
}

package gg.literal.runtime;

import gg.literal.log.TerminalLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class BanList {

    private final Set<String> banned = ConcurrentHashMap.newKeySet();
    private final Path file;

    public BanList(final Path file) {
        this.file = file;
    }

    public void load() {
        if (Files.notExists(file)) {
            return;
        }
        try {
            final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (final String line : lines) {
                final String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    banned.add(trimmed);
                }
            }
            if (!banned.isEmpty()) {
                TerminalLogger.info("Loaded " + banned.size() + " banned IP(s) from " + file.getFileName());
            }
        } catch (IOException ex) {
            TerminalLogger.error("Failed to read ban list: " + ex.getMessage());
        }
    }

    public boolean isBanned(final String ip) {
        return banned.contains(ip);
    }

    public boolean ban(final String ip) {
        final boolean added = banned.add(ip);
        if (added) {
            persist();
        }
        return added;
    }

    public boolean unban(final String ip) {
        final boolean removed = banned.remove(ip);
        if (removed) {
            persist();
        }
        return removed;
    }

    public Set<String> all() {
        return Set.copyOf(banned);
    }

    private void persist() {
        try {
            final StringBuilder sb = new StringBuilder("# Vector ban list — one IP per line\n");
            for (final String ip : banned) {
                sb.append(ip).append('\n');
            }
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ex) {
            TerminalLogger.error("Failed to persist ban list: " + ex.getMessage());
        }
    }
}

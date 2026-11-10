package gg.literal.runtime;

import gg.literal.log.TerminalLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * IP / CIDR ban list with optional expiry and reason, persisted to {@code ban-list.txt}.
 *
 * <p>File format, one rule per line: {@code target|expiryEpochMillis|reason}. The expiry and reason
 * are optional, so plain IP-per-line files from older versions load as permanent bans.
 */
public final class BanList {

    /** @param expiresAtMillis epoch millis, or {@code 0} for a permanent ban */
    public record Entry(String target, long expiresAtMillis, String reason) {
        public boolean permanent() {
            return expiresAtMillis == 0L;
        }

        boolean expired(final long now) {
            return expiresAtMillis != 0L && expiresAtMillis <= now;
        }
    }

    private record Rule(Entry entry, Cidr cidr) {
    }

    private final ConcurrentHashMap<String, Rule> rules = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Rule> cidrRules = new CopyOnWriteArrayList<>();
    private final Path file;
    private final Object ioLock = new Object();

    public BanList(final Path file) {
        this.file = file;
    }

    public void load() {
        if (Files.notExists(file)) {
            return;
        }
        final List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            TerminalLogger.error("Failed to read ban list: " + ex.getMessage());
            return;
        }

        final long now = System.currentTimeMillis();
        boolean droppedExpired = false;
        for (final String raw : lines) {
            final String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            final String[] parts = line.split("\\|", 3);
            final Cidr cidr = Cidr.parse(parts[0].trim());
            if (cidr == null) {
                TerminalLogger.warn("Ignoring invalid ban list entry: " + parts[0]);
                continue;
            }
            long expiresAt = 0L;
            if (parts.length > 1 && !parts[1].isBlank()) {
                try {
                    expiresAt = Long.parseLong(parts[1].trim());
                } catch (NumberFormatException ex) {
                    TerminalLogger.warn("Ignoring ban list entry with bad expiry: " + line);
                    continue;
                }
            }
            final String reason = parts.length > 2 ? parts[2].trim() : "";
            final Entry entry = new Entry(cidr.toString(), expiresAt, reason);
            if (entry.expired(now)) {
                droppedExpired = true;
                continue;
            }
            put(entry, cidr);
        }
        if (!rules.isEmpty()) {
            TerminalLogger.info("Loaded " + rules.size() + " ban(s) from " + file.getFileName());
        }
        if (droppedExpired) {
            persist();
        }
    }

    /** Returns the active ban covering {@code ip}, or {@code null}. Expired bans are dropped on the way. */
    public Entry find(final String ip) {
        final long now = System.currentTimeMillis();
        final Rule exact = rules.get(ip);
        if (exact != null) {
            if (!exact.entry().expired(now)) {
                return exact.entry();
            }
            expire(exact);
        }
        if (cidrRules.isEmpty()) {
            return null;
        }
        final byte[] address = Cidr.addressBytes(ip);
        if (address == null) {
            return null;
        }
        for (final Rule rule : cidrRules) {
            if (rule.cidr().contains(address)) {
                if (!rule.entry().expired(now)) {
                    return rule.entry();
                }
                expire(rule);
            }
        }
        return null;
    }

    public boolean isBanned(final String ip) {
        return find(ip) != null;
    }

    /**
     * Adds or replaces a ban.
     *
     * @param target         IP or CIDR block
     * @param durationMillis ban length, or {@code 0} for permanent
     * @return the stored entry, or {@code null} if {@code target} is not a valid IP / CIDR
     */
    public Entry ban(final String target, final long durationMillis, final String reason) {
        final Cidr cidr = Cidr.parse(target);
        if (cidr == null) {
            return null;
        }
        final long expiresAt = durationMillis > 0 ? System.currentTimeMillis() + durationMillis : 0L;
        final Entry entry = new Entry(cidr.toString(), expiresAt, reason == null ? "" : reason.trim());
        put(entry, cidr);
        persist();
        return entry;
    }

    /** Removes the ban for {@code target}; returns whether one existed. */
    public boolean unban(final String target) {
        final Cidr cidr = Cidr.parse(target);
        final String key = cidr == null ? target : cidr.toString();
        final Rule removed = rules.remove(key);
        if (removed == null) {
            return false;
        }
        cidrRules.remove(removed);
        persist();
        return true;
    }

    /** Active bans, sorted by target. */
    public List<Entry> all() {
        final long now = System.currentTimeMillis();
        final List<Entry> out = new ArrayList<>();
        for (final Rule rule : rules.values()) {
            if (!rule.entry().expired(now)) {
                out.add(rule.entry());
            }
        }
        out.sort(Comparator.comparing(Entry::target));
        return out;
    }

    /** Removes expired bans. Returns how many were dropped. */
    public int purgeExpired() {
        final long now = System.currentTimeMillis();
        int removed = 0;
        for (final Rule rule : rules.values()) {
            if (rule.entry().expired(now)) {
                expire(rule);
                removed++;
            }
        }
        return removed;
    }

    private void put(final Entry entry, final Cidr cidr) {
        final Rule rule = new Rule(entry, cidr);
        final Rule previous = rules.put(entry.target(), rule);
        if (previous != null) {
            cidrRules.remove(previous);
        }
        if (!cidr.isSingleHost()) {
            cidrRules.add(rule);
        }
    }

    private void expire(final Rule rule) {
        if (rules.remove(rule.entry().target(), rule)) {
            cidrRules.remove(rule);
            persist();
        }
    }

    private void persist() {
        synchronized (ioLock) {
            final StringBuilder sb = new StringBuilder(
                "# Vector ban list - target|expiryEpochMillis(0=permanent)|reason\n");
            for (final Rule rule : rules.values()) {
                final Entry e = rule.entry();
                sb.append(e.target()).append('|').append(e.expiresAtMillis()).append('|')
                    .append(e.reason().replace('\n', ' ').replace('\r', ' ')).append('\n');
            }
            try {
                Files.writeString(file, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException ex) {
                TerminalLogger.error("Failed to persist ban list: " + ex.getMessage());
            }
        }
    }
}

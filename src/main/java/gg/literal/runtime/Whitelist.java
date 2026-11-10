package gg.literal.runtime;

import gg.literal.log.TerminalLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Allow-list of usernames, IPs and CIDR blocks stored in {@code whitelist.txt} (one entry per line).
 * Only consulted when {@code whitelist-enabled} is set. A player may log in if their username
 * <em>or</em> their IP matches an entry.
 */
public final class Whitelist {

    private final ConcurrentHashMap<String, String> names = new ConcurrentHashMap<>(); // lowercase -> as typed
    private final ConcurrentHashMap<String, Cidr> addresses = new ConcurrentHashMap<>(); // canonical -> network
    private final CopyOnWriteArrayList<Cidr> networks = new CopyOnWriteArrayList<>();
    private final Path file;
    private final Object ioLock = new Object();

    public Whitelist(final Path file) {
        this.file = file;
    }

    /** (Re)reads the file, replacing the in-memory contents. */
    public void load() {
        names.clear();
        addresses.clear();
        networks.clear();
        if (Files.notExists(file)) {
            return;
        }
        try {
            for (final String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                final String line = raw.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    insert(line);
                }
            }
        } catch (IOException ex) {
            TerminalLogger.error("Failed to read whitelist: " + ex.getMessage());
            return;
        }
        if (size() > 0) {
            TerminalLogger.info("Loaded " + size() + " whitelist entr" + (size() == 1 ? "y" : "ies")
                + " from " + file.getFileName());
        }
    }

    public boolean isAllowed(final String username, final String ip) {
        if (username != null && names.containsKey(username.toLowerCase(Locale.ROOT))) {
            return true;
        }
        if (ip == null) {
            return false;
        }
        if (addresses.containsKey(ip)) {
            return true;
        }
        if (networks.isEmpty()) {
            return false;
        }
        final byte[] bytes = Cidr.addressBytes(ip);
        for (final Cidr network : networks) {
            if (network.contains(bytes)) {
                return true;
            }
        }
        return false;
    }

    /** Adds a username, IP or CIDR. Returns the stored form, or {@code null} if it was already present. */
    public String add(final String entry) {
        final String stored = insert(entry.trim());
        if (stored != null) {
            persist();
        }
        return stored;
    }

    public boolean remove(final String entry) {
        final String trimmed = entry.trim();
        boolean removed = names.remove(trimmed.toLowerCase(Locale.ROOT)) != null;
        final Cidr cidr = Cidr.parse(trimmed);
        if (cidr != null) {
            final Cidr hit = cidr.isSingleHost() ? addresses.remove(cidr.toString()) : null;
            if (hit != null) {
                removed = true;
            } else {
                for (final Cidr network : networks) {
                    if (network.toString().equals(cidr.toString())) {
                        networks.remove(network);
                        removed = true;
                    }
                }
            }
        }
        if (removed) {
            persist();
        }
        return removed;
    }

    public List<String> entries() {
        final List<String> out = new ArrayList<>(names.values());
        out.addAll(addresses.keySet());
        for (final Cidr network : networks) {
            out.add(network.toString());
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    public int size() {
        return names.size() + addresses.size() + networks.size();
    }

    private String insert(final String entry) {
        final Cidr cidr = Cidr.parse(entry);
        if (cidr == null) {
            if (entry.isEmpty() || entry.chars().anyMatch(Character::isWhitespace)) {
                return null;
            }
            return names.putIfAbsent(entry.toLowerCase(Locale.ROOT), entry) == null ? entry : null;
        }
        if (cidr.isSingleHost()) {
            return addresses.putIfAbsent(cidr.toString(), cidr) == null ? cidr.toString() : null;
        }
        for (final Cidr existing : networks) {
            if (existing.toString().equals(cidr.toString())) {
                return null;
            }
        }
        networks.add(cidr);
        return cidr.toString();
    }

    private void persist() {
        synchronized (ioLock) {
            final StringBuilder sb = new StringBuilder("# Vector whitelist - usernames, IPs or CIDR blocks, one per line\n");
            for (final String entry : entries()) {
                sb.append(entry).append('\n');
            }
            try {
                Files.writeString(file, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException ex) {
                TerminalLogger.error("Failed to persist whitelist: " + ex.getMessage());
            }
        }
    }
}

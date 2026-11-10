package gg.literal.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class HostPatternsTest {

    @Test
    void emptyListMatchesNothing() {
        assertTrue(HostPatterns.of(List.of()).isEmpty());
        assertFalse(HostPatterns.NONE.matches("play.example.com"));
    }

    @Test
    void exactNamesAreCaseInsensitive() {
        final HostPatterns patterns = HostPatterns.of(List.of("Play.Example.com"));
        assertTrue(patterns.matches("play.example.com"));
        assertFalse(patterns.matches("other.example.com"));
    }

    @Test
    void wildcardsMatchSubdomainsOnly() {
        final HostPatterns patterns = HostPatterns.of(List.of("*.example.com"));
        assertTrue(patterns.matches("a.example.com"));
        assertTrue(patterns.matches("a.b.example.com"));
        assertFalse(patterns.matches("example.com"));
        assertFalse(patterns.matches("badexample.com"));
    }

    @Test
    void starMatchesEverything() {
        assertTrue(HostPatterns.of(List.of("*")).matches("203.0.113.9"));
        assertTrue(HostPatterns.of(List.of("*")).matches(""));
    }

    @Test
    void ipLiteralsMatchOnlyWhenListed() {
        final HostPatterns patterns = HostPatterns.of(List.of("play.example.com", "203.0.113.9"));
        assertTrue(patterns.matches("203.0.113.9"));
        assertFalse(patterns.matches("203.0.113.10"));
    }
}

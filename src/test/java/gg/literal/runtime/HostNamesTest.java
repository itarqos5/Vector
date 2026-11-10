package gg.literal.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HostNamesTest {

    @Test
    void lowerCasesAndTrims() {
        assertEquals("play.example.com", HostNames.normalize("  Play.Example.COM "));
    }

    @Test
    void dropsTheTrailingRootDot() {
        assertEquals("play.example.com", HostNames.normalize("play.example.com."));
    }

    @Test
    void stripsForgeMarkers() {
        assertEquals("play.example.com", HostNames.normalize("play.example.com\0FML\0"));
        assertEquals("play.example.com", HostNames.normalize("play.example.com\0FML2\0"));
    }

    @Test
    void handlesNullAndEmptyInput() {
        assertEquals("", HostNames.normalize(null));
        assertEquals("", HostNames.normalize(""));
        assertEquals("", HostNames.normalize("\0FML\0"));
    }

    @Test
    void ipLiteralsPassThrough() {
        assertEquals("203.0.113.5", HostNames.normalize("203.0.113.5"));
    }
}

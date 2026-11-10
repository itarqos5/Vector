package gg.literal.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.literal.config.VectorConfig.Route;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RouteTableTest {

    private static Route route(final String host, final String... backends) {
        return new Route(host, List.of(backends));
    }

    @Test
    void emptyTableRestrictsNothing() {
        assertTrue(RouteTable.of(List.of()).isEmpty());
        assertNull(RouteTable.EMPTY.lookup("anything.example.com"));
    }

    @Test
    void exactHostsMatchCaseInsensitivelyOnTheBackendNames() {
        final RouteTable table = RouteTable.of(List.of(route("lobby.example.com", "Lobby-1", "lobby-2")));
        assertEquals(Set.of("lobby-1", "lobby-2"), table.lookup("lobby.example.com"));
        assertNull(table.lookup("play.example.com"));
    }

    @Test
    void wildcardsMatchSubdomainsButNotTheBareDomain() {
        final RouteTable table = RouteTable.of(List.of(route("*.example.com", "a")));
        assertEquals(Set.of("a"), table.lookup("play.example.com"));
        assertEquals(Set.of("a"), table.lookup("deep.sub.example.com"));
        assertNull(table.lookup("example.com"));
        assertNull(table.lookup("notexample.com"));
    }

    @Test
    void exactBeatsWildcardAndLongerWildcardBeatsShorter() {
        final RouteTable table = RouteTable.of(List.of(
            route("*.example.com", "general"),
            route("*.eu.example.com", "eu"),
            route("vip.eu.example.com", "vip")));
        assertEquals(Set.of("vip"), table.lookup("vip.eu.example.com"));
        assertEquals(Set.of("eu"), table.lookup("play.eu.example.com"));
        assertEquals(Set.of("general"), table.lookup("play.us.example.com"));
    }

    @Test
    void catchAllAppliesLast() {
        final RouteTable table = RouteTable.of(List.of(route("*", "fallback"), route("lobby.example.com", "lobby")));
        assertEquals(Set.of("lobby"), table.lookup("lobby.example.com"));
        assertEquals(Set.of("fallback"), table.lookup("203.0.113.9"));
        assertEquals(Set.of("fallback"), table.lookup(""));
    }
}

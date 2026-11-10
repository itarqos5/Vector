package gg.literal.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.literal.config.Json;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SyntheticStatusTest {

    private static Map<String, Object> decode(final byte[] packet) {
        final ByteBuf buf = Unpooled.wrappedBuffer(packet);
        McProtocol.tryReadVarInt(buf); // length
        assertEquals(McProtocol.PACKET_STATUS_RESPONSE, McProtocol.tryReadVarInt(buf));
        final int length = McProtocol.tryReadVarInt(buf);
        return Json.parseObject(buf.readCharSequence(length, StandardCharsets.UTF_8).toString());
    }

    @Test
    void echoesTheClientProtocolSoTheListEntryIsNotMarkedIncompatible() {
        final SyntheticStatus status = new SyntheticStatus("Offline", () -> "down");
        final Map<String, Object> json = decode(status.packet(767));
        assertEquals(767L, ((Map<?, ?>) json.get("version")).get("protocol"));
        assertEquals("Offline", ((Map<?, ?>) json.get("version")).get("name"));
        assertEquals(0L, ((Map<?, ?>) json.get("players")).get("online"));
        assertEquals("down", ((Map<?, ?>) json.get("description")).get("text"));
    }

    @Test
    void cachesPerProtocolAndRebuildsWhenTheMotdChanges() {
        final AtomicReference<String> motd = new AtomicReference<>("one");
        final SyntheticStatus status = new SyntheticStatus("Maintenance", motd::get);

        final byte[] first = status.packet(767);
        assertSame(first, status.packet(767), "same protocol is served from the cache");
        assertNotSame(first, status.packet(765), "another protocol gets its own packet");

        motd.set("two");
        final byte[] changed = status.packet(767);
        assertNotSame(first, changed);
        assertTrue(new String(changed, StandardCharsets.UTF_8).contains("two"));
    }

    @Test
    void escapesSpecialCharactersInTheMotd() {
        final SyntheticStatus status = new SyntheticStatus("Offline", () -> "say \"hi\"\nplease");
        final Map<String, Object> json = decode(status.packet(1));
        assertEquals("say \"hi\"\nplease", ((Map<?, ?>) json.get("description")).get("text"));
    }
}

package gg.literal.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class McProtocolTest {

    @Test
    void varIntsRoundTrip() {
        for (final int value : new int[] {0, 1, 127, 128, 255, 25565, 2_097_151, Integer.MAX_VALUE}) {
            final ByteBuf buf = Unpooled.buffer();
            McProtocol.writeVarInt(buf, value);
            assertEquals(McProtocol.varIntSize(value), buf.readableBytes(), "size of " + value);
            assertEquals(value, McProtocol.tryReadVarInt(buf));
            assertEquals(0, buf.readableBytes());
        }
    }

    @Test
    void incompleteVarIntRestoresTheReaderIndex() {
        final ByteBuf buf = Unpooled.buffer();
        buf.writeByte(0x80);
        buf.writeByte(0x80); // continuation bits but no terminator
        assertEquals(-1, McProtocol.tryReadVarInt(buf));
        assertEquals(0, buf.readerIndex());
    }

    @Test
    void overlongVarIntIsRejected() {
        final ByteBuf buf = Unpooled.buffer();
        for (int i = 0; i < 6; i++) {
            buf.writeByte(0xFF);
        }
        assertEquals(-1, McProtocol.tryReadVarInt(buf));
    }

    @Test
    void stringPacketHasLengthIdAndUtf8Payload() {
        final ByteBuf packet = McProtocol.stringPacket(UnpooledByteBufAllocator.DEFAULT, 0x00, "héllo");
        final byte[] expectedText = "héllo".getBytes(StandardCharsets.UTF_8);

        final int length = McProtocol.tryReadVarInt(packet);
        assertEquals(packet.readableBytes(), length);
        assertEquals(0x00, McProtocol.tryReadVarInt(packet));
        assertEquals(expectedText.length, McProtocol.tryReadVarInt(packet));
        final byte[] text = new byte[expectedText.length];
        packet.readBytes(text);
        assertArrayEquals(expectedText, text);
        packet.release();
    }

    @Test
    void pongEchoesTheEightBytePayload() {
        final ByteBuf pong = McProtocol.pongPacket(UnpooledByteBufAllocator.DEFAULT, 0x1122334455667788L);
        assertEquals(9, McProtocol.tryReadVarInt(pong));
        assertEquals(McProtocol.PACKET_PING_RESPONSE, McProtocol.tryReadVarInt(pong));
        assertEquals(0x1122334455667788L, pong.readLong());
        pong.release();
    }

    @Test
    void loginDisconnectWrapsTheReasonInATextComponent() {
        final ByteBuf packet = McProtocol.loginDisconnect(UnpooledByteBufAllocator.DEFAULT, "No way");
        McProtocol.tryReadVarInt(packet); // length
        assertEquals(McProtocol.PACKET_LOGIN_DISCONNECT, McProtocol.tryReadVarInt(packet));
        final int textLength = McProtocol.tryReadVarInt(packet);
        final String json = packet.readCharSequence(textLength, StandardCharsets.UTF_8).toString();
        assertEquals("{\"text\":\"No way\"}", json);
        packet.release();
    }
}

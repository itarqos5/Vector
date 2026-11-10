package gg.literal.runtime;

import gg.literal.config.Json;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;

import java.nio.charset.StandardCharsets;

/** Small helpers for the handful of Minecraft packets Vector crafts or parses itself. */
final class McProtocol {

    static final int PACKET_STATUS_RESPONSE = 0x00;
    static final int PACKET_PING_RESPONSE = 0x01;
    static final int PACKET_LOGIN_DISCONNECT = 0x00;

    private McProtocol() {
    }

    /**
     * Reads a VarInt, or returns -1 (and restores the reader index) if the buffer does not hold a
     * complete, well-formed one.
     */
    static int tryReadVarInt(final ByteBuf buf) {
        final int start = buf.readerIndex();
        int result = 0;
        int shift = 0;
        while (shift < 35) {
            if (!buf.isReadable()) {
                buf.readerIndex(start);
                return -1;
            }
            final int b = buf.readByte() & 0xFF;
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
        }
        buf.readerIndex(start);
        return -1;
    }

    static void writeVarInt(final ByteBuf buf, int value) {
        while (true) {
            if ((value & ~0x7F) == 0) {
                buf.writeByte(value);
                return;
            }
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    static int varIntSize(final int value) {
        int size = 1;
        int v = value >>> 7;
        while (v != 0) {
            size++;
            v >>>= 7;
        }
        return size;
    }

    /** Builds an uncompressed packet: {@code [length][id][string]}. */
    static ByteBuf stringPacket(final ByteBufAllocator alloc, final int packetId, final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        final int bodyLength = varIntSize(packetId) + varIntSize(bytes.length) + bytes.length;
        final ByteBuf out = alloc.buffer(varIntSize(bodyLength) + bodyLength);
        writeVarInt(out, bodyLength);
        writeVarInt(out, packetId);
        writeVarInt(out, bytes.length);
        out.writeBytes(bytes);
        return out;
    }

    /** Same as {@link #stringPacket} but returns raw bytes, for packets that are built once and reused. */
    static byte[] stringPacketBytes(final int packetId, final String value) {
        final ByteBuf packet = stringPacket(UnpooledByteBufAllocator.DEFAULT, packetId, value);
        try {
            final byte[] bytes = new byte[packet.readableBytes()];
            packet.readBytes(bytes);
            return bytes;
        } finally {
            packet.release();
        }
    }

    /** Status Ping response: echoes the 8 byte payload. */
    static ByteBuf pongPacket(final ByteBufAllocator alloc, final long payload) {
        final ByteBuf out = alloc.buffer(10);
        writeVarInt(out, 9);
        writeVarInt(out, PACKET_PING_RESPONSE);
        out.writeLong(payload);
        return out;
    }

    /** Login Disconnect (login state, never compressed) carrying a plain text reason. */
    static ByteBuf loginDisconnect(final ByteBufAllocator alloc, final String reason) {
        final StringBuilder json = new StringBuilder("{\"text\":");
        Json.writeString(json, reason);
        json.append('}');
        return stringPacket(alloc, PACKET_LOGIN_DISCONNECT, json.toString());
    }
}

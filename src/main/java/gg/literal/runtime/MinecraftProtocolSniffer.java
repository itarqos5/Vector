package gg.literal.runtime;

import gg.literal.log.TerminalLogger;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Peeks at the Minecraft Java Edition 1.21.x protocol (client -> server direction)
 * to log player joins, leaves, chat messages, and commands.
 * Does NOT modify or consume the data stream — all bytes are passed through unchanged.
 *
 * Limitation: only works for un-encrypted sessions.  Online-mode backends start
 * AES/CFB8 encryption after the Encryption Response, which makes subsequent
 * packet contents opaque.  Join and leave are always logged; chat/commands are
 * only visible in offline-mode or velocity forwarding-mode setups.
 */
public final class MinecraftProtocolSniffer extends ChannelInboundHandlerAdapter {

    private enum State { HANDSHAKE, STATUS, LOGIN, CONFIGURATION, PLAY, IGNORED }

    private State state = State.HANDSHAKE;
    private String playerName = null;
    private String clientIp = "?";

    // Private accumulation buffer — not shared, so no ref-count problems.
    private final ByteBuf accumulator = Unpooled.buffer(512);

    // -------------------------------------------------------------------------
    // Netty lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void channelActive(final ChannelHandlerContext ctx) {
        if (ctx.channel().remoteAddress() instanceof InetSocketAddress addr) {
            clientIp = addr.getAddress().getHostAddress();
        }
        ctx.fireChannelActive();
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
        if (msg instanceof ByteBuf data && state != State.IGNORED && state != State.STATUS) {
            // Copy into our private buffer WITHOUT consuming the original ByteBuf.
            accumulator.writeBytes(data.slice());
            tryParse();
        }
        ctx.fireChannelRead(msg); // always pass bytes through unchanged
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) {
        if (playerName != null) {
            TerminalLogger.info("[LEAVE] " + playerName + " (" + clientIp + ") disconnected");
            playerName = null;
        }
        accumulator.release();
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
        // Stop sniffing on any pipeline error; let the real handler deal with it.
        state = State.IGNORED;
        accumulator.clear();
        ctx.fireExceptionCaught(cause);
    }

    // -------------------------------------------------------------------------
    // Packet parsing
    // -------------------------------------------------------------------------

    private void tryParse() {
        while (accumulator.isReadable()) {
            final int savedIdx = accumulator.readerIndex();

            // Read length-prefix VarInt
            final int length = tryReadVarInt();
            if (length < 0) break; // not enough bytes yet

            if (accumulator.readableBytes() < length) {
                accumulator.readerIndex(savedIdx); // restore to before length VarInt
                break; // incomplete packet; wait for more data
            }

            final int packetEnd = accumulator.readerIndex() + length;

            // Read packet ID VarInt (part of the length-counted bytes)
            final int packetId = tryReadVarInt();
            if (packetId >= 0) {
                try {
                    dispatch(packetId);
                } catch (Exception ignored) {
                    // Malformed packet or encryption has started — give up sniffing.
                    state = State.IGNORED;
                    accumulator.clear();
                    return;
                }
            }

            accumulator.readerIndex(packetEnd); // advance regardless
        }
        accumulator.discardSomeReadBytes(); // prevent unbounded growth
    }

    private void dispatch(final int packetId) {
        switch (state) {
            case HANDSHAKE -> {
                if (packetId == 0x00) {
                    readVarInt();     // protocol version  (discard)
                    readString();    // server address    (discard)
                    accumulator.skipBytes(2); // server port — unsigned short
                    final int nextState = readVarInt();
                    state = switch (nextState) {
                        case 1 -> State.STATUS;
                        case 2, 3 -> State.LOGIN;  // 3 = Transfer (1.20.5+)
                        default -> State.IGNORED;
                    };
                }
            }
            case LOGIN -> {
                if (packetId == 0x00) { // Login Start
                    final String name = readString(); // player name (max 16 chars)
                    // Skip UUID field — we only need the name
                    if (name != null && !name.isBlank()) {
                        playerName = name;
                        TerminalLogger.info("[JOIN] " + name + " (" + clientIp + ") is connecting");
                    }
                } else if (packetId == 0x03) { // Login Acknowledged (1.20.2+)
                    state = State.CONFIGURATION;
                }
            }
            case CONFIGURATION -> {
                if (packetId == 0x03) { // Acknowledge Configuration (1.20.2+)
                    state = State.PLAY;
                }
            }
            case PLAY -> {
                if (packetId == 0x04) { // Chat Command (1.19+, C->S)
                    final String cmd = readString(); // command without leading /
                    if (cmd != null && !cmd.isBlank() && playerName != null) {
                        TerminalLogger.info("[CMD] " + playerName + " ran: /" + cmd);
                    }
                } else if (packetId == 0x05) { // Chat Message (1.19+, C->S)
                    final String msg = readString();
                    if (msg != null && !msg.isBlank() && playerName != null) {
                        TerminalLogger.info("[CHAT] " + playerName + ": " + msg);
                    }
                }
            }
            default -> { /* STATUS, IGNORED — no-op */ }
        }
    }

    // -------------------------------------------------------------------------
    // VarInt / String helpers
    // -------------------------------------------------------------------------

    /**
     * Reads a VarInt from the accumulator.
     * Returns -1 and restores the reader index if there are not enough bytes.
     */
    private int tryReadVarInt() {
        final int start = accumulator.readerIndex();
        int result = 0;
        int shift = 0;
        while (shift < 35) {
            if (!accumulator.isReadable()) {
                accumulator.readerIndex(start);
                return -1;
            }
            final int b = accumulator.readByte() & 0xFF;
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
        }
        accumulator.readerIndex(start);
        return -1; // malformed / >5 bytes
    }

    /**
     * Reads a VarInt unconditionally (used inside a confirmed-complete packet).
     * Throws if the VarInt is malformed.
     */
    private int readVarInt() {
        int result = 0, shift = 0;
        while (shift < 35) {
            final int b = accumulator.readByte() & 0xFF;
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
        }
        throw new IllegalStateException("VarInt overflow");
    }

    /**
     * Reads a Minecraft String (VarInt length prefix + UTF-8 bytes).
     * Returns null if the declared length exceeds available bytes.
     */
    private String readString() {
        final int len = readVarInt();
        if (len < 0 || accumulator.readableBytes() < len) return null;
        final byte[] bytes = new byte[len];
        accumulator.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}

package gg.literal.runtime;

import gg.literal.log.TerminalLogger;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.AttributeKey;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Peeks at the Minecraft Java Edition 1.21.x protocol (client -> server direction)
 * to log player joins, leaves, chat messages, and commands.
 * Also intercepts the server -> client direction during LOGIN to detect compression.
 * Does NOT modify or consume the data stream — all bytes are passed through unchanged.
 *
 * Limitation: only works for un-encrypted sessions.  Online-mode backends start
 * AES/CFB8 encryption after the Encryption Response, which makes subsequent
 * packet contents opaque.  Join and leave are always logged; chat/commands are
 * only visible in offline-mode or velocity forwarding-mode setups.
 */
public final class MinecraftProtocolSniffer extends ChannelDuplexHandler {

    /** Client's Minecraft protocol version, extracted from the Handshake packet. */
    public static final AttributeKey<Integer> ATTR_PROTOCOL_VERSION =
            AttributeKey.newInstance("vec-protocol");

    /** Whether the backend enabled packet compression (S\u2192C Set Compression seen). */
    public static final AttributeKey<Boolean> ATTR_COMPRESSION =
            AttributeKey.newInstance("vec-compression");

    private enum State { HANDSHAKE, STATUS, LOGIN, CONFIGURATION, PLAY, IGNORED }

    private State state = State.HANDSHAKE;
    private String playerName = null;
    private String clientIp = "?";
    private int protocolVersion = -1;
    private boolean compressionEnabled = false;
    /** True while S\u2192C is still in the LOGIN phase (after Handshake, before Login Acknowledged). */
    private boolean outLoginPhase = false;

    // C\u2192S accumulation buffer — not shared, so no ref-count problems.
    private final ByteBuf accumulator = Unpooled.buffer(512);
    // S\u2192C accumulation buffer — used only during LOGIN to detect Set Compression.
    private final ByteBuf outboundAccumulator = Unpooled.buffer(256);

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
            tryParse(ctx);
        }
        ctx.fireChannelRead(msg); // always pass bytes through unchanged
    }

    /**
     * Intercepts S\u2192C bytes during the LOGIN phase to detect the Set Compression packet.
     * Must forward the data unchanged by calling ctx.write(msg, promise).
     */
    @Override
    public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) throws Exception {
        if (msg instanceof ByteBuf data && outLoginPhase && !compressionEnabled) {
            outboundAccumulator.writeBytes(data.slice());
            tryParseOutbound(ctx);
        }
        ctx.write(msg, promise);
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) {
        if (playerName != null) {
            TerminalLogger.info("[LEAVE] " + playerName + " (" + clientIp + ") disconnected");
            playerName = null;
        }
        accumulator.release();
        outboundAccumulator.release();
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
        // Stop sniffing on any pipeline error; let the real handler deal with it.
        state = State.IGNORED;
        outLoginPhase = false;
        accumulator.clear();
        outboundAccumulator.clear();
        ctx.fireExceptionCaught(cause);
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    /** Returns true for valid Minecraft usernames: 3-16 chars, [a-zA-Z0-9_] only. */
    private static boolean isValidMinecraftName(final String name) {
        if (name == null || name.length() < 3 || name.length() > 16) return false;
        for (final char c : name.toCharArray()) {
            if (c != '_' && !Character.isLetterOrDigit(c)) return false;
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Packet parsing
    // -------------------------------------------------------------------------

    private void tryParse(final ChannelHandlerContext ctx) {
        while (accumulator.isReadable()) {
            final int savedIdx = accumulator.readerIndex();
            final int length = tryReadVarInt(accumulator);
            if (length < 0) break;

            if (accumulator.readableBytes() < length) {
                accumulator.readerIndex(savedIdx);
                break;
            }

            final int packetEnd = accumulator.readerIndex() + length;
            final int packetId = tryReadVarInt(accumulator);
            if (packetId >= 0) {
                try {
                    dispatch(packetId, ctx);
                } catch (Exception ignored) {
                    state = State.IGNORED;
                    accumulator.clear();
                    return;
                }
            }
            accumulator.readerIndex(packetEnd);
        }
        accumulator.discardSomeReadBytes();
    }

    private void dispatch(final int packetId, final ChannelHandlerContext ctx) {
        switch (state) {
            case HANDSHAKE -> {
                if (packetId == 0x00) {
                    protocolVersion = readVarInt(accumulator);
                    ctx.channel().attr(ATTR_PROTOCOL_VERSION).set(protocolVersion);
                    readString(accumulator);          // server address (discard)
                    accumulator.skipBytes(2);         // server port — unsigned short
                    final int nextState = readVarInt(accumulator);
                    state = switch (nextState) {
                        case 1 -> State.STATUS;
                        case 2, 3 -> { outLoginPhase = true; yield State.LOGIN; } // 3 = Transfer (1.20.5+)
                        default -> State.IGNORED;
                    };
                }
            }
            case LOGIN -> {
                if (packetId == 0x00) { // Login Start
                    final String name = readString(accumulator);
                    if (isValidMinecraftName(name)) {
                        playerName = name;
                        TerminalLogger.info("[JOIN] " + name + " (" + clientIp + ") is connecting");
                    } else {
                        state = State.IGNORED;
                    }
                } else if (packetId == 0x01) { // Encryption Response (online-mode)
                    state = State.IGNORED;
                } else if (packetId == 0x03) { // Login Acknowledged (1.20.2+)
                    outLoginPhase = false;
                    state = State.CONFIGURATION;
                }
            }
            case CONFIGURATION -> {
                if (packetId == 0x03) { // Acknowledge Configuration (1.20.2+)
                    state = State.PLAY;
                }
            }
            case PLAY -> {
                if (packetId == 0x04) { // Chat Command (C\u2192S)
                    final String cmd = readString(accumulator);
                    if (cmd != null && !cmd.isBlank() && playerName != null) {
                        TerminalLogger.info("[CMD] " + playerName + " ran: /" + cmd);
                    }
                } else if (packetId == 0x05) { // Chat Message (C\u2192S)
                    final String msg = readString(accumulator);
                    if (msg != null && !msg.isBlank() && playerName != null) {
                        TerminalLogger.info("[CHAT] " + playerName + ": " + msg);
                    }
                }
            }
            default -> { /* STATUS, IGNORED — no-op */ }
        }
    }

    // -------------------------------------------------------------------------
    // S\u2192C packet parsing (LOGIN phase only — compression detection)
    // -------------------------------------------------------------------------

    private void tryParseOutbound(final ChannelHandlerContext ctx) {
        while (outboundAccumulator.isReadable()) {
            final int savedIdx = outboundAccumulator.readerIndex();
            final int length = tryReadVarInt(outboundAccumulator);
            if (length < 0) break;

            if (outboundAccumulator.readableBytes() < length) {
                outboundAccumulator.readerIndex(savedIdx);
                break;
            }

            final int packetEnd = outboundAccumulator.readerIndex() + length;
            final int packetId = tryReadVarInt(outboundAccumulator);
            if (packetId == 0x03) { // Set Compression (S\u2192C, LOGIN)
                final int threshold = tryReadVarInt(outboundAccumulator);
                if (threshold >= 0) {
                    compressionEnabled = true;
                    ctx.channel().attr(ATTR_COMPRESSION).set(Boolean.TRUE);
                }
            }
            outboundAccumulator.readerIndex(packetEnd);
        }
        outboundAccumulator.discardSomeReadBytes();
    }

    // -------------------------------------------------------------------------
    // VarInt / String helpers (buffer-parameterized)
    // -------------------------------------------------------------------------

    /**
     * Reads a VarInt from {@code buf}.
     * Returns -1 and restores the reader index if there are not enough bytes or the VarInt is malformed.
     */
    private static int tryReadVarInt(final ByteBuf buf) {
        final int start = buf.readerIndex();
        int result = 0, shift = 0;
        while (shift < 35) {
            if (!buf.isReadable()) {
                buf.readerIndex(start);
                return -1;
            }
            final int b = buf.readByte() & 0xFF;
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
        }
        buf.readerIndex(start);
        return -1; // malformed (>5 bytes)
    }

    /**
     * Reads a VarInt unconditionally from {@code buf} (used inside a confirmed-complete packet).
     * Throws if the VarInt is malformed.
     */
    private static int readVarInt(final ByteBuf buf) {
        int result = 0, shift = 0;
        while (shift < 35) {
            final int b = buf.readByte() & 0xFF;
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
        }
        throw new IllegalStateException("VarInt overflow");
    }

    /**
     * Reads a Minecraft String (VarInt length prefix + UTF-8 bytes) from {@code buf}.
     * Returns null if the declared length exceeds available bytes.
     */
    private static String readString(final ByteBuf buf) {
        final int len = readVarInt(buf);
        if (len < 0 || buf.readableBytes() < len) return null;
        final byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}

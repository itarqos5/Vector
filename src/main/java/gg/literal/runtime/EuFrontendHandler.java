package gg.literal.runtime;

import gg.literal.config.VectorConfig;
import gg.literal.log.TerminalLogger;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.haproxy.HAProxyCommand;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyMessageEncoder;
import io.netty.handler.codec.haproxy.HAProxyProxiedProtocol;
import io.netty.handler.codec.haproxy.HAProxyProtocolVersion;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Front door of every client connection.
 *
 * <p>The backend is chosen lazily: the first bytes are buffered until the sniffer has seen the handshake.
 * Server list pings may then be answered locally from the {@link StatusService}; everything else is
 * relayed to a backend picked by the {@link BackendPool}, with failover to another backend if the connect
 * fails. Buffered bytes are replayed once the upstream connection is ready.
 */
public final class EuFrontendHandler extends ChannelInboundHandlerAdapter {

    /** Name of the backend a connection is currently routed to (for the {@code list} command). */
    public static final AttributeKey<String> ATTR_BACKEND = AttributeKey.newInstance("vec-backend");

    /** Channels are marked unwritable above 128 KiB of queued output and writable again below 32 KiB. */
    static final WriteBufferWaterMark WATER_MARK = new WriteBufferWaterMark(32 * 1024, 128 * 1024);

    private static final int MAX_UNDECIDED_BYTES = 1024;
    private static final int MAX_STATUS_BUFFER = 256;
    private static final long UNDECIDED_TIMEOUT_MS = 1000L;
    private static final long STATUS_TIMEOUT_MS = 5000L;

    private enum Mode { UNDECIDED, STATUS, PROXY }

    private final VectorContext vc;

    private Mode mode = Mode.UNDECIDED;
    private boolean closed;
    private String clientAddressKey;
    private String clientIp;

    /** Bytes received before the upstream connection was ready, replayed in order afterwards. */
    private final ArrayDeque<Object> pending = new ArrayDeque<>();
    private int pendingBytes;
    private ScheduledFuture<?> decideTimeout;
    private ScheduledFuture<?> handshakeTimer;

    // --- local status mode
    private ByteBuf statusBuf;
    private StatusService.Decision statusDecision;
    private boolean statusAnswered;
    private ScheduledFuture<?> statusTimeout;

    // --- proxy mode
    private VectorConfig cfg;
    private volatile Channel outboundChannel;
    private boolean backendReady;
    private boolean flushNeeded;
    private Backend held;
    private final Set<Backend> tried = new HashSet<>();
    private int attempts;

    public EuFrontendHandler(final VectorContext vc) {
        this.vc = vc;
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    public void channelActive(final ChannelHandlerContext ctx) {
        final Channel inbound = ctx.channel();
        clientAddressKey = ConnectionRegistry.toAddressKey(inbound);
        clientIp = ConnectionRegistry.toIp(inbound);
        vc.registry.register(clientAddressKey, inbound);
        Metrics.INSTANCE.connectionsTotal.increment();
        // Nothing is connected yet: the backend is picked once the handshake has been parsed.

        final int handshakeTimeoutMs = vc.config.get().policy().handshakeTimeoutMs();
        if (handshakeTimeoutMs > 0) {
            handshakeTimer = ctx.executor().schedule(() -> {
                if (mode == Mode.UNDECIDED && pendingBytes == 0 && !closed) {
                    Metrics.INSTANCE.timeouts.increment();
                    TerminalLogger.warn("Closing " + clientIp + ": no handshake within " + handshakeTimeoutMs + "ms");
                    ctx.close();
                }
            }, handshakeTimeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
        switch (mode) {
            case UNDECIDED -> {
                queue(msg);
                decide(ctx, false);
            }
            case STATUS -> handleStatusInput(ctx, msg);
            case PROXY -> {
                if (backendReady) {
                    forward(ctx.channel(), outboundChannel, msg);
                } else {
                    queue(msg);
                }
            }
        }
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) {
        closed = true;
        cancel(handshakeTimer);
        cancel(decideTimeout);
        cancel(statusTimeout);
        releasePending();
        if (statusBuf != null) {
            statusBuf.release();
            statusBuf = null;
        }
        if (clientAddressKey != null) {
            vc.registry.unregister(clientAddressKey);
        }
        releaseHeld();

        final Channel out = outboundChannel;
        if (out != null) {
            if (out.isActive()) {
                closeOnFlush(out);
            } else {
                out.close(); // still connecting: abort
            }
        }
    }

    @Override
    public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) throws Exception {
        if (evt instanceof IdleStateEvent idle && idle.state() == IdleState.READER_IDLE) {
            Metrics.INSTANCE.timeouts.increment();
            TerminalLogger.warn("Closing idle connection from " + clientIp + " (no data for "
                + vc.config.get().policy().idleTimeoutSeconds() + "s)");
            ctx.close();
            return;
        }
        ctx.fireUserEventTriggered(evt);
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
        TerminalLogger.error("Frontend exception: " + cause.getMessage());
        closeOnFlush(ctx.channel());
    }

    // =========================================================================
    // Deciding what the connection is
    // =========================================================================

    private void decide(final ChannelHandlerContext ctx, final boolean force) {
        if (mode != Mode.UNDECIDED || closed) {
            return;
        }
        Integer nextState = ctx.channel().attr(MinecraftProtocolSniffer.ATTR_NEXT_STATE).get();
        if (nextState == null) {
            if (!force && pendingBytes < MAX_UNDECIDED_BYTES) {
                // Handshake not fully seen yet. Wait briefly; legacy/odd clients fall through to the proxy path.
                if (decideTimeout == null) {
                    decideTimeout = ctx.executor().schedule(() -> decide(ctx, true), UNDECIDED_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                }
                return;
            }
            nextState = 0;
        }
        cancel(decideTimeout);

        if (nextState == 1) {
            final StatusService.Decision decision = vc.status.decide();
            if (decision != StatusService.Decision.PASSTHROUGH) {
                beginLocalStatus(ctx, decision);
                return;
            }
            Metrics.INSTANCE.statusFromBackend.increment();
        }
        beginProxy(ctx, nextState != 1);
    }

    // =========================================================================
    // Local status responses
    // =========================================================================

    private void beginLocalStatus(final ChannelHandlerContext ctx, final StatusService.Decision decision) {
        mode = Mode.STATUS;
        statusDecision = decision;
        statusBuf = ctx.alloc().buffer();
        while (!pending.isEmpty()) {
            final Object msg = pending.poll();
            if (msg instanceof ByteBuf buf) {
                statusBuf.writeBytes(buf);
            }
            ReferenceCountUtil.release(msg);
        }
        pendingBytes = 0;

        // Drop the handshake packet; what follows is Status Request / Ping Request.
        final int handshakeLength = McProtocol.tryReadVarInt(statusBuf);
        if (handshakeLength < 0 || statusBuf.readableBytes() < handshakeLength) {
            ctx.close();
            return;
        }
        statusBuf.skipBytes(handshakeLength);

        statusTimeout = ctx.executor().schedule(() -> {
            ctx.close();
        }, STATUS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        processStatus(ctx);
    }

    private void handleStatusInput(final ChannelHandlerContext ctx, final Object msg) {
        try {
            if (msg instanceof ByteBuf buf && statusBuf != null) {
                statusBuf.writeBytes(buf);
            }
        } finally {
            ReferenceCountUtil.release(msg);
        }
        processStatus(ctx);
    }

    private void processStatus(final ChannelHandlerContext ctx) {
        while (statusBuf != null && !closed) {
            final int length = McProtocol.tryReadVarInt(statusBuf);
            if (length < 0) {
                break; // incomplete
            }
            final int packetStart = statusBuf.readerIndex();
            if (length < 1 || length > 16) {
                ctx.close();
                return;
            }
            if (statusBuf.readableBytes() < length) {
                statusBuf.readerIndex(packetStart - McProtocol.varIntSize(length));
                break;
            }
            final int packetId = McProtocol.tryReadVarInt(statusBuf);
            if (packetId == 0x00 && length == 1 && !statusAnswered) {
                statusAnswered = true;
                final Integer protocol = ctx.channel().attr(MinecraftProtocolSniffer.ATTR_PROTOCOL_VERSION).get();
                ctx.writeAndFlush(vc.status.response(statusDecision, protocol == null ? -1 : protocol, ctx.alloc()));
            } else if (packetId == 0x01 && length == 9) {
                ctx.writeAndFlush(McProtocol.pongPacket(ctx.alloc(), statusBuf.readLong()))
                    .addListener(ChannelFutureListener.CLOSE);
                return;
            } else {
                ctx.close();
                return;
            }
        }
        if (statusBuf != null) {
            statusBuf.discardReadBytes();
            if (statusBuf.readableBytes() > MAX_STATUS_BUFFER) {
                ctx.close();
            }
        }
    }

    // =========================================================================
    // Proxying
    // =========================================================================

    private void beginProxy(final ChannelHandlerContext ctx, final boolean honourOverride) {
        mode = Mode.PROXY;
        cfg = vc.config.get();
        ctx.channel().config().setAutoRead(false);

        // One-shot backend override set by the "send" console command.
        final InetSocketAddress override = honourOverride ? vc.registry.pollNextBackend(clientIp) : null;
        connect(ctx, override);
    }

    private void connect(final ChannelHandlerContext ctx, final InetSocketAddress override) {
        final Channel inbound = ctx.channel();

        final Backend chosen;
        final String host;
        final int port;
        if (override != null) {
            host = override.getHostString();
            port = override.getPort();
            chosen = vc.pool.find(host, port);
            if (chosen != null) {
                vc.pool.acquire(chosen);
            }
        } else {
            chosen = vc.pool.select(clientIp, tried);
            if (chosen == null) {
                TerminalLogger.warn("No healthy backend available for " + clientIp
                    + (tried.isEmpty() ? "" : " (after " + tried.size() + " failed attempt(s))"));
                reject(ctx);
                return;
            }
            host = chosen.host();
            port = chosen.port();
        }
        held = chosen;
        inbound.attr(ATTR_BACKEND).set(chosen != null ? chosen.name() : host + ":" + port);

        final long startedAt = System.nanoTime();
        final Bootstrap bootstrap = new Bootstrap()
            .group(inbound.eventLoop())
            .channel(NioSocketChannel.class)
            .option(ChannelOption.TCP_NODELAY, true)
            .option(ChannelOption.SO_KEEPALIVE, true)
            .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
            .option(ChannelOption.WRITE_BUFFER_WATER_MARK, WATER_MARK)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, cfg.connectTimeoutMs())
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(final SocketChannel ch) {
                    if (cfg.proxyProtocol()) {
                        ch.pipeline().addLast("proxy-protocol-v2", HAProxyMessageEncoder.INSTANCE);
                    }
                    ch.pipeline().addLast("backend-relay", new BackendRelayHandler(inbound));
                }
            });

        final ChannelFuture connectFuture = bootstrap.connect(host, port);
        outboundChannel = connectFuture.channel();

        connectFuture.addListener((ChannelFutureListener) future -> {
            if (closed) {
                future.channel().close();
                return;
            }
            if (!future.isSuccess()) {
                onConnectFailed(ctx, chosen, override, host, port, future.cause());
                return;
            }
            if (chosen != null) {
                vc.pool.report(chosen, true, (System.nanoTime() - startedAt) / 1_000_000L);
            }

            final Channel outbound = future.channel();
            if (cfg.proxyProtocol()) {
                outbound.writeAndFlush(buildProxyV2Header(inbound, outbound)).addListener((ChannelFutureListener) write -> {
                    if (!write.isSuccess()) {
                        TerminalLogger.warn("Failed sending Proxy Protocol header for "
                            + formatAddress(inbound.remoteAddress()));
                        closeOnFlush(inbound);
                        closeOnFlush(outbound);
                        return;
                    }
                    if (outbound.pipeline().get("proxy-protocol-v2") != null) {
                        outbound.pipeline().remove("proxy-protocol-v2");
                    }
                    onBackendReady(inbound, outbound);
                });
            } else {
                onBackendReady(inbound, outbound);
            }
        });
    }

    private void onConnectFailed(
        final ChannelHandlerContext ctx,
        final Backend chosen,
        final InetSocketAddress override,
        final String host,
        final int port,
        final Throwable cause
    ) {
        final String reason = cause == null ? "unknown" : cause.getClass().getSimpleName() + ": " + cause.getMessage();
        TerminalLogger.warn("Backend connection failed from " + formatAddress(ctx.channel().remoteAddress())
            + " to " + host + ":" + port + " (" + reason + ")");
        releaseHeld();
        outboundChannel = null;

        if (chosen != null) {
            tried.add(chosen);
            vc.pool.report(chosen, false, -1L);
        }

        attempts++;
        if (override == null && attempts < cfg.backends().maxConnectAttempts()) {
            connect(ctx, null); // fail over to another backend
            return;
        }
        reject(ctx);
    }

    private void onBackendReady(final Channel inbound, final Channel outbound) {
        if (closed) {
            outbound.close();
            return;
        }
        backendReady = true;
        Object msg;
        while ((msg = pending.poll()) != null) {
            forward(inbound, outbound, msg);
        }
        flushUpstream();
        pendingBytes = 0;
        inbound.config().setAutoRead(true);
    }

    /** Closes the client, telling players in the login phase why instead of just dropping them. */
    private void reject(final ChannelHandlerContext ctx) {
        Metrics.INSTANCE.noBackendAvailable.increment();
        releasePending();
        final Integer nextState = ctx.channel().attr(MinecraftProtocolSniffer.ATTR_NEXT_STATE).get();
        final String message = vc.config.get().status().offlineMotd();
        if (nextState != null && (nextState == 2 || nextState == 3) && !message.isEmpty() && ctx.channel().isActive()) {
            ctx.writeAndFlush(McProtocol.loginDisconnect(ctx.alloc(), message)).addListener(ChannelFutureListener.CLOSE);
        } else {
            closeOnFlush(ctx.channel());
        }
    }

    private void forward(final Channel inbound, final Channel outbound, final Object msg) {
        if (outbound == null || !outbound.isActive()) {
            ReferenceCountUtil.release(msg);
            return;
        }
        if (msg instanceof ByteBuf buf) {
            Metrics.INSTANCE.bytesFromClients.add(buf.readableBytes());
        }
        // Writes are batched: the flush happens once per read loop in channelReadComplete.
        flushNeeded = true;
        outbound.write(msg).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                closeOnFlush(outbound);
                closeOnFlush(inbound);
            }
        });
    }

    /**
     * Backpressure: when the client socket cannot keep up, stop reading from the backend until it drains,
     * so a slow client cannot make Vector buffer an unbounded amount of backend output.
     */
    @Override
    public void channelWritabilityChanged(final ChannelHandlerContext ctx) {
        final Channel out = outboundChannel;
        if (backendReady && out != null) {
            out.config().setAutoRead(ctx.channel().isWritable());
        }
        ctx.fireChannelWritabilityChanged();
    }

    @Override
    public void channelReadComplete(final ChannelHandlerContext ctx) {
        flushUpstream();
        ctx.fireChannelReadComplete();
    }

    private void flushUpstream() {
        if (flushNeeded) {
            flushNeeded = false;
            final Channel out = outboundChannel;
            if (out != null && out.isActive()) {
                out.flush();
            }
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void queue(final Object msg) {
        pending.add(msg);
        if (msg instanceof ByteBuf buf) {
            pendingBytes += buf.readableBytes();
        }
    }

    private void releasePending() {
        Object msg;
        while ((msg = pending.poll()) != null) {
            ReferenceCountUtil.release(msg);
        }
        pendingBytes = 0;
    }

    private void releaseHeld() {
        final Backend backend = held;
        if (backend != null) {
            held = null;
            vc.pool.release(backend);
        }
    }

    private static void cancel(final ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    private static HAProxyMessage buildProxyV2Header(final Channel inbound, final Channel outbound) {
        if (!(inbound.remoteAddress() instanceof InetSocketAddress source)
            || !(outbound.remoteAddress() instanceof InetSocketAddress destination)) {
            return new HAProxyMessage(
                HAProxyProtocolVersion.V2,
                HAProxyCommand.PROXY,
                HAProxyProxiedProtocol.UNKNOWN,
                null,
                null,
                0,
                0
            );
        }

        final InetAddress sourceAddress = source.getAddress();
        final InetAddress destinationAddress = destination.getAddress();
        final HAProxyProxiedProtocol protocol = resolveProtocol(sourceAddress, destinationAddress);

        return new HAProxyMessage(
            HAProxyProtocolVersion.V2,
            HAProxyCommand.PROXY,
            protocol,
            sourceAddress.getHostAddress(),
            destinationAddress.getHostAddress(),
            source.getPort(),
            destination.getPort()
        );
    }

    private static HAProxyProxiedProtocol resolveProtocol(final InetAddress source, final InetAddress destination) {
        if (source instanceof Inet4Address && destination instanceof Inet4Address) {
            return HAProxyProxiedProtocol.TCP4;
        }
        if (source instanceof Inet6Address && destination instanceof Inet6Address) {
            return HAProxyProxiedProtocol.TCP6;
        }
        return HAProxyProxiedProtocol.UNKNOWN;
    }

    static void closeOnFlush(final Channel channel) {
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(channel.alloc().buffer(0)).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private static String formatAddress(final Object address) {
        if (address instanceof InetSocketAddress socketAddress) {
            return socketAddress.getAddress().getHostAddress() + ":" + socketAddress.getPort();
        }
        return String.valueOf(address);
    }
}

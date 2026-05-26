package gg.literal.runtime;

import gg.literal.config.VectorConfig;
import gg.literal.log.TerminalLogger;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.haproxy.HAProxyCommand;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyMessageEncoder;
import io.netty.handler.codec.haproxy.HAProxyProxiedProtocol;
import io.netty.handler.codec.haproxy.HAProxyProtocolVersion;
import io.netty.util.ReferenceCountUtil;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;

public final class EuFrontendHandler extends ChannelInboundHandlerAdapter {

    private final VectorConfig config;
    private volatile Channel outboundChannel;

    public EuFrontendHandler(final VectorConfig config) {
        this.config = config;
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) {
        final Channel inboundChannel = ctx.channel();
        inboundChannel.config().setAutoRead(false);

        final Bootstrap bootstrap = new Bootstrap()
            .group(inboundChannel.eventLoop())
            .channel(NioSocketChannel.class)
            .option(ChannelOption.TCP_NODELAY, true)
            .option(ChannelOption.SO_KEEPALIVE, true)
            .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.connectTimeoutMs())
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(final SocketChannel ch) {
                    ch.pipeline().addLast("proxy-protocol-v2", HAProxyMessageEncoder.INSTANCE);
                    ch.pipeline().addLast("backend-relay", new BackendRelayHandler(inboundChannel));
                }
            });

        final ChannelFuture connectFuture = bootstrap.connect(config.backendHost(), config.backendPort());
        outboundChannel = connectFuture.channel();

        connectFuture.addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                TerminalLogger.warn("Backend connection failed from " + formatAddress(inboundChannel.remoteAddress())
                    + " to " + config.backendHost() + ":" + config.backendPort());
                closeOnFlush(inboundChannel);
                return;
            }

            final HAProxyMessage header = buildProxyV2Header(inboundChannel, outboundChannel);
            outboundChannel.writeAndFlush(header).addListener((ChannelFutureListener) writeFuture -> {
                if (!writeFuture.isSuccess()) {
                    TerminalLogger.warn("Failed sending Proxy Protocol header for "
                        + formatAddress(inboundChannel.remoteAddress()));
                    closeOnFlush(inboundChannel);
                    closeOnFlush(outboundChannel);
                    return;
                }
                if (outboundChannel.pipeline().get("proxy-protocol-v2") != null) {
                    outboundChannel.pipeline().remove("proxy-protocol-v2");
                }
                inboundChannel.config().setAutoRead(true);
            });
        });
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
        final Channel out = outboundChannel;
        if (out != null && out.isActive()) {
            out.writeAndFlush(msg).addListener((ChannelFutureListener) future -> {
                if (!future.isSuccess()) {
                    closeOnFlush(out);
                    closeOnFlush(ctx.channel());
                }
            });
            return;
        }
        ReferenceCountUtil.release(msg);
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) {
        closeOnFlush(outboundChannel);
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
        TerminalLogger.error("Frontend exception: " + cause.getMessage());
        closeOnFlush(ctx.channel());
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

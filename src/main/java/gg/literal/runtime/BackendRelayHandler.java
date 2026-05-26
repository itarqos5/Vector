package gg.literal.runtime;

import gg.literal.log.TerminalLogger;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

public final class BackendRelayHandler extends ChannelInboundHandlerAdapter {

    private final Channel inboundChannel;
    private volatile boolean dataReceived;

    public BackendRelayHandler(final Channel inboundChannel) {
        this.inboundChannel = inboundChannel;
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
        dataReceived = true;
        if (inboundChannel.isActive()) {
            inboundChannel.writeAndFlush(msg).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
            return;
        }
        ReferenceCountUtil.release(msg);
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) {
        if (!dataReceived) {
            TerminalLogger.warn("Backend closed connection immediately after handshake from "
                + formatRemote(inboundChannel)
                + " — if proxy-protocol=true, ensure Velocity has proxy-protocol=true in velocity.toml");
        }
        EuFrontendHandler.closeOnFlush(inboundChannel);
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
        TerminalLogger.error("Backend relay exception: " + cause.getMessage());
        EuFrontendHandler.closeOnFlush(ctx.channel());
    }

    private static String formatRemote(final Channel channel) {
        if (channel.remoteAddress() instanceof java.net.InetSocketAddress addr) {
            return addr.getAddress().getHostAddress() + ":" + addr.getPort();
        }
        return String.valueOf(channel.remoteAddress());
    }
}

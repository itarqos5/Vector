package gg.literal.runtime;

import gg.literal.log.TerminalLogger;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ReferenceCountUtil;

import java.net.InetSocketAddress;

public final class PacketLimiterHandler extends ChannelDuplexHandler {

    private static final long WINDOW_NANOS = 1_000_000_000L;

    private final long maxBytesPerSecond;
    private final long maxPacketsPerSecond;

    private long windowStart = System.nanoTime();
    private long bytesInWindow;
    private long packetsInWindow;

    public PacketLimiterHandler(final long maxBytesPerSecond, final long maxPacketsPerSecond) {
        this.maxBytesPerSecond = Math.max(1L, maxBytesPerSecond);
        this.maxPacketsPerSecond = Math.max(1L, maxPacketsPerSecond);
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
        final long now = System.nanoTime();
        if (now - windowStart >= WINDOW_NANOS) {
            windowStart = now;
            bytesInWindow = 0L;
            packetsInWindow = 0L;
        }

        packetsInWindow++;
        bytesInWindow += estimateBytes(msg);

        if (packetsInWindow > maxPacketsPerSecond || bytesInWindow > maxBytesPerSecond) {
            final String offender = formatRemote(ctx);
            TerminalLogger.warn("Rate-limiter drop: " + offender
                + " exceeded threshold packets=" + packetsInWindow + "/" + maxPacketsPerSecond
                + " bytes=" + bytesInWindow + "/" + maxBytesPerSecond);
            ReferenceCountUtil.release(msg);
            ctx.close();
            return;
        }

        ctx.fireChannelRead(msg);
    }

    private static long estimateBytes(final Object msg) {
        if (msg instanceof ByteBuf byteBuf) {
            return byteBuf.readableBytes();
        }
        if (msg instanceof ByteBufHolder holder) {
            return holder.content().readableBytes();
        }
        return 1L;
    }

    private static String formatRemote(final ChannelHandlerContext ctx) {
        if (ctx.channel().remoteAddress() instanceof InetSocketAddress address) {
            return address.getAddress().getHostAddress() + ":" + address.getPort();
        }
        return String.valueOf(ctx.channel().remoteAddress());
    }
}

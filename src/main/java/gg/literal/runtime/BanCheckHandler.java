package gg.literal.runtime;

import gg.literal.log.TerminalLogger;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.net.InetSocketAddress;

@ChannelHandler.Sharable
public final class BanCheckHandler extends ChannelInboundHandlerAdapter {

    private final BanList banList;

    public BanCheckHandler(final BanList banList) {
        this.banList = banList;
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) {
        if (ctx.channel().remoteAddress() instanceof InetSocketAddress addr) {
            final String ip = addr.getAddress().getHostAddress();
            if (banList.isBanned(ip)) {
                TerminalLogger.warn("Blocked banned connection from " + ip);
                ctx.close();
                return;
            }
        }
        ctx.fireChannelActive();
    }
}

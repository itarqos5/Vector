package gg.literal.runtime;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.net.InetSocketAddress;
import java.text.SimpleDateFormat;
import java.util.Date;

@ChannelHandler.Sharable
public final class BanCheckHandler extends ChannelInboundHandlerAdapter {

    private final BanList banList;
    private final ThrottledLog log = new ThrottledLog(5_000);

    public BanCheckHandler(final BanList banList) {
        this.banList = banList;
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) {
        if (ctx.channel().remoteAddress() instanceof InetSocketAddress addr) {
            final String ip = addr.getAddress().getHostAddress();
            final BanList.Entry ban = banList.find(ip);
            if (ban != null) {
                Metrics.INSTANCE.blockedBan.increment();
                log.warn("Blocked banned connection from " + ip + describe(ban));
                ctx.close();
                return;
            }
        }
        ctx.fireChannelActive();
    }

    private static String describe(final BanList.Entry ban) {
        final StringBuilder sb = new StringBuilder(" [").append(ban.target());
        if (!ban.permanent()) {
            sb.append(", until ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(ban.expiresAtMillis())));
        }
        if (!ban.reason().isEmpty()) {
            sb.append(", ").append(ban.reason());
        }
        return sb.append(']').toString();
    }
}

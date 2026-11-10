package gg.literal.runtime;

import gg.literal.config.VectorConfig;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.net.InetSocketAddress;

/** Enforces the per-IP concurrent connection cap and new-connection rate limit. */
public final class ConnectionLimitHandler extends ChannelInboundHandlerAdapter {

    private static final ThrottledLog LOG = new ThrottledLog(5_000);

    private final ConnectionLimiter limiter;
    private final ConfigHolder config;
    private String countedIp;

    public ConnectionLimitHandler(final ConnectionLimiter limiter, final ConfigHolder config) {
        this.limiter = limiter;
        this.config = config;
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) {
        if (!(ctx.channel().remoteAddress() instanceof InetSocketAddress addr)) {
            ctx.fireChannelActive();
            return;
        }
        final String ip = addr.getAddress().getHostAddress();
        final VectorConfig.Limits limits = config.get().limits();
        final ConnectionLimiter.Result result = limiter.tryAcquire(ip, limits);

        if (result == ConnectionLimiter.Result.OK) {
            countedIp = ip;
            ctx.fireChannelActive();
            return;
        }

        if (result == ConnectionLimiter.Result.TOO_FAST) {
            Metrics.INSTANCE.blockedConnectionRate.increment();
            LOG.warn("Connection rate limit: " + ip + " exceeded " + limits.maxConnectionRatePerIp()
                + " connections per " + limits.connectionRateWindowMs() + "ms");
        } else {
            Metrics.INSTANCE.blockedConnectionLimit.increment();
            LOG.warn("Connection limit: " + ip + " already has " + limits.maxConnectionsPerIp() + " open connections");
        }
        ctx.close();
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) {
        if (countedIp != null) {
            limiter.release(countedIp);
            countedIp = null;
        }
        ctx.fireChannelInactive();
    }
}

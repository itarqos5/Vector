package gg.literal.runtime;

import gg.literal.config.VectorConfig;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;

public final class SecurePipelineInitializer extends ChannelInitializer<SocketChannel> {

    private final VectorConfig config;
    private final ConnectionRegistry registry;
    private final BanCheckHandler banCheckHandler;

    public SecurePipelineInitializer(final VectorConfig config, final ConnectionRegistry registry, final BanList banList) {
        this.config = config;
        this.registry = registry;
        this.banCheckHandler = new BanCheckHandler(banList);
    }

    @Override
    protected void initChannel(final SocketChannel channel) {
        channel.pipeline().addLast("ban-check", banCheckHandler);
        channel.pipeline().addLast("packet-limiter", new PacketLimiterHandler(
            config.maxBytesPerSecond(),
            config.maxPacketsPerSecond()
        ));
        channel.pipeline().addLast("eu-frontend", new EuFrontendHandler(config, registry));
    }
}

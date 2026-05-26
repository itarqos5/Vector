package gg.literal.runtime;

import gg.literal.config.VectorConfig;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;

public final class SecurePipelineInitializer extends ChannelInitializer<SocketChannel> {

    private final VectorConfig config;

    public SecurePipelineInitializer(final VectorConfig config) {
        this.config = config;
    }

    @Override
    protected void initChannel(final SocketChannel channel) {
        channel.pipeline().addLast("packet-limiter", new PacketLimiterHandler(
            config.maxBytesPerSecond(),
            config.maxPacketsPerSecond()
        ));
        channel.pipeline().addLast("eu-frontend", new EuFrontendHandler(config));
    }
}

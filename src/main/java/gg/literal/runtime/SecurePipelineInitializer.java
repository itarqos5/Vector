package gg.literal.runtime;

import gg.literal.config.VectorConfig;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;

public final class SecurePipelineInitializer extends ChannelInitializer<SocketChannel> {

    private final VectorContext context;
    private final BanCheckHandler banCheckHandler;

    public SecurePipelineInitializer(final VectorContext context) {
        this.context = context;
        this.banCheckHandler = new BanCheckHandler(context.banList);
    }

    @Override
    protected void initChannel(final SocketChannel channel) {
        // Limits are read per connection so a config reload applies to every new connection.
        final VectorConfig config = context.config.get();
        channel.pipeline().addLast("ban-check", banCheckHandler);
        channel.pipeline().addLast("connection-limit", new ConnectionLimitHandler(context.limiter, context.config));
        channel.pipeline().addLast("mc-sniffer", new MinecraftProtocolSniffer());
        channel.pipeline().addLast("packet-limiter", new PacketLimiterHandler(
            config.maxBytesPerSecond(),
            config.maxPacketsPerSecond()
        ));
        channel.pipeline().addLast("eu-frontend", new EuFrontendHandler(context));
    }
}

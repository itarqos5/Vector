package gg.literal.runtime;

import gg.literal.config.VectorConfig;
import gg.literal.log.TerminalLogger;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.AdaptiveRecvByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

public final class VectorRuntime {

    private VectorRuntime() {
    }

    public static void boot(final VectorConfig config) {
        EventLoopGroup bossGroup = null;
        EventLoopGroup workerGroup = null;

        try {
            bossGroup = new NioEventLoopGroup(config.bossThreads());
            workerGroup = new NioEventLoopGroup(config.workerThreads());

            final ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 8192)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .childOption(ChannelOption.RCVBUF_ALLOCATOR, new AdaptiveRecvByteBufAllocator(64, 2048, 65536))
                .childHandler(new SecurePipelineInitializer(config));

            final Channel server = bootstrap.bind(config.bindHost(), config.bindPort()).syncUninterruptibly().channel();
            TerminalLogger.info(
                "Vector online on " + config.bindHost() + ":" + config.bindPort()
                    + " -> " + config.backendHost() + ":" + config.backendPort()
                    + " | boss=" + config.bossThreads() + " worker=" + config.workerThreads()
            );

            server.closeFuture().syncUninterruptibly();
        } catch (Throwable throwable) {
            TerminalLogger.fatal("Netty runtime failed: " + throwable.getMessage());
        } finally {
            if (workerGroup != null) {
                workerGroup.shutdownGracefully().syncUninterruptibly();
            }
            if (bossGroup != null) {
                bossGroup.shutdownGracefully().syncUninterruptibly();
            }
        }
    }
}

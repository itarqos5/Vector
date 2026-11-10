package gg.literal.runtime;

import gg.literal.config.VectorConfig;
import gg.literal.console.VectorConsole;
import gg.literal.detect.BackendDetector;
import gg.literal.log.TerminalLogger;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.AdaptiveRecvByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class VectorRuntime {

    private VectorRuntime() {
    }

    public static void boot(final VectorConfig config) {
        final ConfigHolder holder = new ConfigHolder(Path.of("config.json"), config);

        final BanList banList = new BanList(Path.of("ban-list.txt"));
        banList.load();
        final Whitelist whitelist = new Whitelist(Path.of("whitelist.txt"));
        whitelist.load();

        final BackendPool pool = new BackendPool(holder);
        holder.onReload(reloaded -> pool.apply(reloaded.backends()));

        final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, runnable -> {
            final Thread thread = new Thread(runnable, "vector-scheduler");
            thread.setDaemon(true);
            return thread;
        });

        final HealthChecker healthChecker = new HealthChecker(pool, holder, scheduler);
        final StatusService statusService = new StatusService(pool, holder, scheduler);
        final ConnectionLimiter limiter = new ConnectionLimiter();
        final ConnectionRegistry registry = new ConnectionRegistry();
        final MetricsHistory history = new MetricsHistory(registry);
        final AutoBan autoBan = new AutoBan(holder, banList, registry);
        final VectorContext context = new VectorContext(
            holder, registry, banList, whitelist, pool, limiter, statusService, history, autoBan);

        probeBackends(config, pool, healthChecker);

        statusService.start();
        healthChecker.start();
        history.start(scheduler);
        scheduler.scheduleWithFixedDelay(() -> {
            limiter.sweep(holder.get().limits().connectionRateWindowMs());
            autoBan.sweep();
            banList.purgeExpired();
        }, 30, 30, TimeUnit.SECONDS);

        if (config.metrics().enabled()) {
            new MetricsServer(context).start();
        }
        if (config.dashboard().enabled()) {
            new DashboardServer(context).start();
        }
        new VectorConsole(context).start();

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
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, EuFrontendHandler.WATER_MARK)
                .childOption(ChannelOption.RCVBUF_ALLOCATOR, new AdaptiveRecvByteBufAllocator(64, 2048, 65536))
                .childHandler(new SecurePipelineInitializer(context));

            final Channel server = bootstrap.bind(config.bindHost(), config.bindPort()).syncUninterruptibly().channel();
            TerminalLogger.info(
                "Vector online on " + config.bindHost() + ":" + config.bindPort()
                    + " -> " + pool.all().size() + " backend(s) [" + config.backends().mode().id() + "]"
                    + " | boss=" + config.bossThreads() + " worker=" + config.workerThreads()
            );

            server.closeFuture().syncUninterruptibly();
        } catch (Throwable throwable) {
            TerminalLogger.fatal("Netty runtime failed: " + throwable.getMessage());
        } finally {
            healthChecker.stop();
            statusService.stop();
            scheduler.shutdownNow();
            if (workerGroup != null) {
                workerGroup.shutdownGracefully().syncUninterruptibly();
            }
            if (bossGroup != null) {
                bossGroup.shutdownGracefully().syncUninterruptibly();
            }
        }
    }

    /** Runs the first health round and identifies each reachable backend's server software. */
    private static void probeBackends(final VectorConfig config, final BackendPool pool, final HealthChecker checker) {
        TerminalLogger.info("Probing " + pool.all().size() + " backend(s)...");
        checker.checkNow();
        for (final Backend backend : pool.all()) {
            if (backend.healthy()) {
                BackendDetector.detect(backend.host(), backend.port(), config.proxyProtocol());
            } else {
                TerminalLogger.warn("Backend " + BackendPool.describe(backend)
                    + " is unreachable at startup; it will be used once it comes up");
            }
        }
    }
}

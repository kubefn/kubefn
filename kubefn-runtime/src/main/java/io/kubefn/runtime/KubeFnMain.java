package io.kubefn.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubefn.runtime.classloader.FunctionLoader;
import io.kubefn.runtime.config.RuntimeConfig;
import io.kubefn.runtime.heap.HeapExchangeImpl;
import io.kubefn.runtime.routing.FunctionRouter;
import io.kubefn.runtime.server.AdminHandler;
import io.kubefn.runtime.server.NettyServer;
import io.kubefn.runtime.watcher.FunctionWatcher;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;

/**
 * KubeFn Runtime — The Living Application Fabric.
 *
 * <p>Entry point that births the organism:
 * <ol>
 *   <li>Load configuration from environment</li>
 *   <li>Initialize HeapExchange (shared object graph fabric)</li>
 *   <li>Start Netty HTTP server (function serving)</li>
 *   <li>Start admin server (health, readiness, introspection)</li>
 *   <li>Load existing function groups (born-warm into the hot JVM)</li>
 *   <li>Start function watcher (hot-reload on file changes)</li>
 *   <li>Register shutdown hook for graceful drain</li>
 * </ol>
 */
public class KubeFnMain {

    private static final Logger log = LoggerFactory.getLogger(KubeFnMain.class);

    public static void main(String[] args) throws Exception {
        log.info("Booting KubeFn organism...");

        // Load config
        RuntimeConfig config = RuntimeConfig.fromEnv();

        // Create the HeapExchange — the shared object graph fabric
        HeapExchangeImpl heapExchange = new HeapExchangeImpl();

        // Shared router — the organism's routing table
        FunctionRouter router = new FunctionRouter();

        // Function loader — brings functions to life inside the organism
        FunctionLoader loader = new FunctionLoader(router, heapExchange);

        // Start main HTTP server
        NettyServer server = new NettyServer(config, router);
        server.start();

        // Start admin server on separate port
        startAdminServer(config, router, server.objectMapper());

        // Load existing function groups from the functions directory
        if (Files.exists(config.functionsDir())) {
            loader.loadAll(config.functionsDir());
        } else {
            Files.createDirectories(config.functionsDir());
            log.info("Created functions directory: {}", config.functionsDir());
        }

        // Start file watcher for hot-reload
        FunctionWatcher watcher = new FunctionWatcher(config.functionsDir(), loader);
        Thread watcherThread = Thread.startVirtualThread(watcher);
        watcherThread.setName("kubefn-watcher");

        log.info("KubeFn organism is ALIVE. {} routes registered.", router.routeCount());
        log.info("Drop function JARs into {} to deploy.", config.functionsDir());

        // Graceful shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received. Draining organism...");
            watcher.stop();
            server.stop();
        }, "kubefn-shutdown"));

        // Block until server closes
        server.awaitTermination();
    }

    private static void startAdminServer(RuntimeConfig config, FunctionRouter router,
                                         ObjectMapper objectMapper) throws InterruptedException {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup(1);

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new HttpServerCodec());
                        pipeline.addLast(new HttpObjectAggregator(65536));
                        pipeline.addLast(new AdminHandler(router, objectMapper));
                    }
                });

        bootstrap.bind(config.adminPort()).sync();
        log.info("Admin server listening on port {}", config.adminPort());
    }
}

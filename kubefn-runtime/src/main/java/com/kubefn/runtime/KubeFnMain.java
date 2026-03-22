package com.kubefn.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kubefn.runtime.classloader.FunctionLoader;
import com.kubefn.runtime.config.RuntimeConfig;
import com.kubefn.runtime.heap.HeapExchangeImpl;
import com.kubefn.runtime.lifecycle.DrainManager;
import com.kubefn.runtime.resilience.FallbackRegistry;
import com.kubefn.runtime.resilience.FunctionCircuitBreaker;
import com.kubefn.runtime.routing.FunctionRouter;
import com.kubefn.runtime.server.AdminHandler;
import com.kubefn.runtime.server.NettyServer;
import com.kubefn.runtime.watcher.FunctionWatcher;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;

/**
 * KubeFn Runtime — Production-grade entry point.
 * All hardening components are wired together:
 * HeapGuard, AuditLog, DrainManager, CircuitBreaker,
 * FallbackRegistry, Metrics, Tracing, Timeout.
 */
public class KubeFnMain {

    private static final Logger log = LoggerFactory.getLogger(KubeFnMain.class);

    private static EventLoopGroup adminBossGroup;
    private static EventLoopGroup adminWorkerGroup;

    public static void main(String[] args) throws Exception {
        log.info("Booting KubeFn organism v0.2...");

        RuntimeConfig config = RuntimeConfig.fromEnv();

        // Core components
        HeapExchangeImpl heapExchange = new HeapExchangeImpl();
        FunctionRouter router = new FunctionRouter();
        FunctionCircuitBreaker circuitBreaker = new FunctionCircuitBreaker();
        FallbackRegistry fallbackRegistry = new FallbackRegistry();
        DrainManager drainManager = new DrainManager();
        FunctionLoader loader = new FunctionLoader(router, heapExchange, drainManager);

        // Start HTTP server with all hardening wired
        NettyServer server = new NettyServer(
                config, router, circuitBreaker, fallbackRegistry, drainManager);
        server.start();

        // Start admin server
        startAdminServer(config, router, server.objectMapper(), heapExchange, circuitBreaker);

        // Load existing functions
        if (Files.exists(config.functionsDir())) {
            loader.loadAll(config.functionsDir());
        } else {
            Files.createDirectories(config.functionsDir());
            log.info("Created functions directory: {}", config.functionsDir());
        }

        // Hot-reload watcher
        FunctionWatcher watcher = new FunctionWatcher(config.functionsDir(), loader);
        Thread.startVirtualThread(watcher);

        log.info("KubeFn organism is ALIVE. {} routes registered.", router.routeCount());
        log.info("Drop function JARs into {} to deploy.", config.functionsDir());

        // Graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received. Draining organism...");
            watcher.stop();
            server.stop();
            if (adminBossGroup != null) adminBossGroup.shutdownGracefully();
            if (adminWorkerGroup != null) adminWorkerGroup.shutdownGracefully();
            log.info("Organism shut down gracefully.");
        }, "kubefn-shutdown"));

        server.awaitTermination();
    }

    private static void startAdminServer(RuntimeConfig config, FunctionRouter router,
                                         ObjectMapper objectMapper, HeapExchangeImpl heapExchange,
                                         FunctionCircuitBreaker circuitBreaker)
            throws InterruptedException {
        adminBossGroup = new NioEventLoopGroup(1);
        adminWorkerGroup = new NioEventLoopGroup(1);

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(adminBossGroup, adminWorkerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new HttpServerCodec());
                        pipeline.addLast(new HttpObjectAggregator(65536));
                        pipeline.addLast(new AdminHandler(
                                router, objectMapper, heapExchange, circuitBreaker));
                    }
                });

        bootstrap.bind(config.adminPort()).sync();
        log.info("Admin server listening on port {}", config.adminPort());
    }
}

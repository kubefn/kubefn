package io.kubefn.runtime.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubefn.runtime.config.RuntimeConfig;
import io.kubefn.runtime.routing.FunctionRouter;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The organism's nervous system. A Netty HTTP server that receives requests
 * and dispatches them to function handlers on virtual threads.
 *
 * <p>Event loops handle I/O only. User function code NEVER runs on event loops.
 * Virtual threads handle blocking work naturally.
 */
public class NettyServer {

    private static final Logger log = LoggerFactory.getLogger(NettyServer.class);

    private final RuntimeConfig config;
    private final FunctionRouter router;
    private final ObjectMapper objectMapper;
    private final ExecutorService functionExecutor;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public NettyServer(RuntimeConfig config, FunctionRouter router) {
        this.config = config;
        this.router = router;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.findAndRegisterModules();

        // Virtual thread executor for function invocation
        // Functions can freely block (JDBC, HTTP, file IO) without starving the pool
        this.functionExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Start the HTTP server. Blocks until the server is bound.
     */
    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new HttpServerCodec());
                        pipeline.addLast(new HttpObjectAggregator(config.maxRequestBodyBytes()));
                        pipeline.addLast(new RequestDispatcher(
                                router, functionExecutor, objectMapper, config));
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 1024)
                .childOption(ChannelOption.SO_KEEPALIVE, true);

        ChannelFuture future = bootstrap.bind(config.port()).sync();
        serverChannel = future.channel();

        log.info("╔══════════════════════════════════════════════╗");
        log.info("║       KubeFn Runtime — Live Application Fabric      ║");
        log.info("╠══════════════════════════════════════════════╣");
        log.info("║  HTTP server listening on port {}            ║", config.port());
        log.info("║  Admin server on port {}                     ║", config.adminPort());
        log.info("║  Functions dir: {}  ║", config.functionsDir());
        log.info("║  Max concurrency/group: {}                  ║", config.maxConcurrencyPerGroup());
        log.info("║  Virtual threads: enabled                           ║");
        log.info("╚══════════════════════════════════════════════╝");
    }

    /**
     * Gracefully shut down the server. Drains in-flight requests.
     */
    public void stop() {
        log.info("Shutting down KubeFn runtime...");
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        functionExecutor.shutdown();
        log.info("KubeFn runtime stopped.");
    }

    /**
     * Block until the server channel closes.
     */
    public void awaitTermination() throws InterruptedException {
        if (serverChannel != null) {
            serverChannel.closeFuture().sync();
        }
    }

    public FunctionRouter router() { return router; }
    public ObjectMapper objectMapper() { return objectMapper; }
    public ExecutorService functionExecutor() { return functionExecutor; }
}

package io.kubefn.runtime.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubefn.runtime.routing.FunctionRouter;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin endpoint handler for health checks, readiness, and introspection.
 * Runs on a separate port to keep admin traffic off the function serving path.
 */
public class AdminHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final FunctionRouter router;
    private final ObjectMapper objectMapper;

    public AdminHandler(FunctionRouter router, ObjectMapper objectMapper) {
        this.router = router;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        String path = request.uri();

        Object responseBody = switch (path) {
            case "/healthz" -> Map.of("status", "alive", "organism", "kubefn");
            case "/readyz" -> {
                boolean ready = router.routeCount() > 0;
                yield Map.of("status", ready ? "ready" : "no_functions_loaded",
                        "functionCount", router.routeCount());
            }
            case "/admin/functions" -> {
                var functions = new java.util.ArrayList<Map<String, String>>();
                router.allRoutes().forEach((key, entry) -> {
                    var fn = new LinkedHashMap<String, String>();
                    fn.put("method", key.method());
                    fn.put("path", key.path());
                    fn.put("group", entry.groupName());
                    fn.put("function", entry.functionName());
                    fn.put("class", entry.className());
                    fn.put("revision", entry.revisionId());
                    functions.add(fn);
                });
                yield Map.of("functions", functions, "count", functions.size());
            }
            case "/admin/status" -> {
                var runtime = ManagementFactory.getRuntimeMXBean();
                var memory = ManagementFactory.getMemoryMXBean();
                var status = new LinkedHashMap<String, Object>();
                status.put("uptime_ms", runtime.getUptime());
                status.put("heap_used_mb", memory.getHeapMemoryUsage().getUsed() / (1024 * 1024));
                status.put("heap_max_mb", memory.getHeapMemoryUsage().getMax() / (1024 * 1024));
                status.put("thread_count", ManagementFactory.getThreadMXBean().getThreadCount());
                status.put("loaded_classes", ManagementFactory.getClassLoadingMXBean().getLoadedClassCount());
                status.put("route_count", router.routeCount());
                status.put("jvm_version", runtime.getSpecVersion());
                status.put("vm_name", runtime.getVmName());
                yield status;
            }
            default -> Map.of("error", "Unknown admin endpoint", "status", 404);
        };

        int status = path.equals("/readyz") && router.routeCount() == 0 ? 503 : 200;
        if (responseBody instanceof Map<?, ?> m && m.containsKey("error")) {
            status = 404;
        }

        byte[] body = objectMapper.writeValueAsBytes(responseBody);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(status),
                Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}

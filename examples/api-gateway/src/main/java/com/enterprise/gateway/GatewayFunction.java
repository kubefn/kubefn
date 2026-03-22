package com.enterprise.gateway;

import com.kubefn.api.*;

import java.util.*;

/**
 * Orchestrates the full API gateway pipeline: rate-limit -> authenticate ->
 * transform -> route -> cache check. Aborts early if any stage rejects
 * the request. Reports detailed per-stage timing and decisions.
 */
@FnRoute(path = "/gw/proxy", methods = {"GET", "POST"})
@FnGroup("api-gateway")
public class GatewayFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        long pipelineStart = System.nanoTime();
        List<Map<String, Object>> stageTiming = new ArrayList<>();
        String abortedAt = null;

        // Stage 1: Rate Limiting
        long stageStart = System.nanoTime();
        var rateLimitResponse = ctx.getFunction(RateLimiterFunction.class).handle(request);
        stageTiming.add(stageEntry("rate_limit", System.nanoTime() - stageStart, rateLimitResponse.statusCode()));

        if (rateLimitResponse.statusCode() == 429) {
            abortedAt = "rate_limit";
            return buildGatewayResponse(request, stageTiming, abortedAt, pipelineStart,
                    rateLimitResponse.statusCode(), rateLimitResponse.body());
        }

        // Stage 2: Authentication
        stageStart = System.nanoTime();
        var authResponse = ctx.getFunction(AuthenticationFunction.class).handle(request);
        stageTiming.add(stageEntry("authentication", System.nanoTime() - stageStart, authResponse.statusCode()));

        if (authResponse.statusCode() == 401 || authResponse.statusCode() == 403) {
            abortedAt = "authentication";
            return buildGatewayResponse(request, stageTiming, abortedAt, pipelineStart,
                    authResponse.statusCode(), authResponse.body());
        }

        // Stage 3: Request Transform
        stageStart = System.nanoTime();
        var transformResponse = ctx.getFunction(RequestTransformFunction.class).handle(request);
        stageTiming.add(stageEntry("transform", System.nanoTime() - stageStart, transformResponse.statusCode()));

        // Stage 4: Routing
        stageStart = System.nanoTime();
        var routeResponse = ctx.getFunction(RoutingFunction.class).handle(request);
        stageTiming.add(stageEntry("routing", System.nanoTime() - stageStart, routeResponse.statusCode()));

        if (routeResponse.statusCode() == 404) {
            abortedAt = "routing";
            return buildGatewayResponse(request, stageTiming, abortedAt, pipelineStart,
                    404, routeResponse.body());
        }

        // Stage 5: Response Cache
        stageStart = System.nanoTime();
        var cacheResponse = ctx.getFunction(ResponseCacheFunction.class).handle(request);
        stageTiming.add(stageEntry("cache", System.nanoTime() - stageStart, cacheResponse.statusCode()));

        // Assemble full gateway response
        var rateLimit = ctx.heap().get("gw:rate-limit", Map.class).orElse(Map.of());
        var authResult = ctx.heap().get("gw:auth-result", Map.class).orElse(Map.of());
        var transformed = ctx.heap().get("gw:transformed-request", Map.class).orElse(Map.of());
        var routeDecision = ctx.heap().get("gw:route-decision", Map.class).orElse(Map.of());
        var cacheResult = ctx.heap().get("gw:cache-result", Map.class).orElse(Map.of());

        long totalNanos = System.nanoTime() - pipelineStart;

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "proxied");
        response.put("correlationId", transformed.get("correlationId"));
        response.put("backend", routeDecision.get("backend"));
        response.put("backendVersion", routeDecision.get("backendVersion"));
        response.put("cacheHit", cacheResult.getOrDefault("cacheHit", false));
        response.put("authenticatedUser", authResult.get("subject"));
        response.put("rateLimitRemaining", rateLimit.get("remainingTokens"));
        response.put("trafficSplit", routeDecision.getOrDefault("trafficSplit", false));
        response.put("_meta", Map.of(
                "pipelineStages", 5,
                "stageTiming", stageTiming,
                "totalTimeMs", String.format("%.3f", totalNanos / 1_000_000.0),
                "totalTimeNanos", totalNanos,
                "heapKeysUsed", ctx.heap().keys().size(),
                "abortedAt", "none",
                "zeroCopy", true,
                "note", "Full gateway pipeline executed in-memory. Zero inter-service calls."
        ));

        return KubeFnResponse.ok(response);
    }

    private KubeFnResponse buildGatewayResponse(KubeFnRequest request,
                                                  List<Map<String, Object>> stageTiming,
                                                  String abortedAt,
                                                  long pipelineStart,
                                                  int statusCode,
                                                  Object rejectionBody) {
        long totalNanos = System.nanoTime() - pipelineStart;

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "rejected");
        response.put("rejectedBy", abortedAt);
        response.put("rejection", rejectionBody);
        response.put("_meta", Map.of(
                "pipelineStages", stageTiming.size(),
                "stageTiming", stageTiming,
                "totalTimeMs", String.format("%.3f", totalNanos / 1_000_000.0),
                "totalTimeNanos", totalNanos,
                "abortedAt", abortedAt,
                "note", "Pipeline aborted early at " + abortedAt + " stage."
        ));

        return KubeFnResponse.status(statusCode).body(response);
    }

    private Map<String, Object> stageEntry(String stage, long nanos, int status) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("stage", stage);
        entry.put("durationNanos", nanos);
        entry.put("durationMs", String.format("%.3f", nanos / 1_000_000.0));
        entry.put("status", status);
        return entry;
    }
}

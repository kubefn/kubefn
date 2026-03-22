package com.enterprise.gateway;

import com.kubefn.api.*;

import java.util.*;

/**
 * Determines the backend destination for the incoming request. Supports path-based
 * routing, weighted traffic splitting (canary), and header-based overrides for
 * testing environments.
 */
@FnRoute(path = "/gw/route", methods = {"POST"})
@FnGroup("api-gateway")
public class RoutingFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    // Route table: path prefix -> backend service configs
    private static final Map<String, List<Map<String, Object>>> ROUTE_TABLE;

    static {
        ROUTE_TABLE = new LinkedHashMap<>();
        ROUTE_TABLE.put("/api/users", List.of(
                Map.of("backend", "user-service.default.svc:8080", "weight", 90, "version", "stable"),
                Map.of("backend", "user-service-canary.default.svc:8080", "weight", 10, "version", "canary")
        ));
        ROUTE_TABLE.put("/api/orders", List.of(
                Map.of("backend", "order-service.default.svc:8080", "weight", 100, "version", "stable")
        ));
        ROUTE_TABLE.put("/api/products", List.of(
                Map.of("backend", "product-service.default.svc:8080", "weight", 80, "version", "stable"),
                Map.of("backend", "product-service-v2.default.svc:8080", "weight", 20, "version", "v2-beta")
        ));
        ROUTE_TABLE.put("/api/v2/users", List.of(
                Map.of("backend", "user-service-v2.default.svc:8080", "weight", 100, "version", "v2")
        ));
        ROUTE_TABLE.put("/api/v2/orders", List.of(
                Map.of("backend", "order-service-v2.default.svc:8080", "weight", 100, "version", "v2")
        ));
    }

    // Header-based override for testing
    private static final Map<String, String> TEST_OVERRIDES = Map.of(
            "staging", "staging-gateway.internal.svc:9090",
            "qa", "qa-gateway.internal.svc:9090",
            "load-test", "loadtest-gateway.internal.svc:9090"
    );

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var transformed = ctx.heap().get("gw:transformed-request", Map.class).orElse(Map.of());
        String path = (String) transformed.getOrDefault("rewrittenPath", request.path());
        Map<String, String> headers = (Map<String, String>) transformed.getOrDefault(
                "transformedHeaders", Map.of());

        // Check for test environment override
        String envOverride = headers.getOrDefault("x-test-env", "");
        if (TEST_OVERRIDES.containsKey(envOverride)) {
            Map<String, Object> testRoute = new LinkedHashMap<>();
            testRoute.put("backend", TEST_OVERRIDES.get(envOverride));
            testRoute.put("environment", envOverride);
            testRoute.put("routeType", "test_override");
            testRoute.put("path", path);
            testRoute.put("matched", true);

            ctx.heap().publish("gw:route-decision", testRoute, Map.class);
            return KubeFnResponse.ok(testRoute);
        }

        // Find matching route by longest prefix match
        String matchedPrefix = null;
        List<Map<String, Object>> backends = null;
        for (var entry : ROUTE_TABLE.entrySet()) {
            if (path.startsWith(entry.getKey())) {
                if (matchedPrefix == null || entry.getKey().length() > matchedPrefix.length()) {
                    matchedPrefix = entry.getKey();
                    backends = entry.getValue();
                }
            }
        }

        if (backends == null) {
            Map<String, Object> noRoute = Map.of(
                    "matched", false,
                    "path", path,
                    "error", "no_route_matched",
                    "message", "No backend found for path: " + path
            );
            ctx.heap().publish("gw:route-decision", noRoute, Map.class);
            return KubeFnResponse.status(404).body(noRoute);
        }

        // Weighted random selection for traffic splitting
        Map<String, Object> selectedBackend = selectByWeight(backends);

        Map<String, Object> routeDecision = new LinkedHashMap<>();
        routeDecision.put("matched", true);
        routeDecision.put("path", path);
        routeDecision.put("matchedPrefix", matchedPrefix);
        routeDecision.put("backend", selectedBackend.get("backend"));
        routeDecision.put("backendVersion", selectedBackend.get("version"));
        routeDecision.put("routeType", backends.size() > 1 ? "weighted_split" : "direct");
        routeDecision.put("availableBackends", backends);
        routeDecision.put("trafficSplit", backends.size() > 1);

        ctx.heap().publish("gw:route-decision", routeDecision, Map.class);
        return KubeFnResponse.ok(routeDecision);
    }

    private Map<String, Object> selectByWeight(List<Map<String, Object>> backends) {
        int totalWeight = backends.stream()
                .mapToInt(b -> ((Number) b.get("weight")).intValue())
                .sum();
        int rand = (int) (Math.random() * totalWeight);
        int cumulative = 0;
        for (Map<String, Object> backend : backends) {
            cumulative += ((Number) backend.get("weight")).intValue();
            if (rand < cumulative) return backend;
        }
        return backends.get(backends.size() - 1);
    }
}

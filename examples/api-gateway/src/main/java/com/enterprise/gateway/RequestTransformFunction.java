package com.enterprise.gateway;

import io.kubefn.api.*;

import java.util.*;

/**
 * Normalizes and enriches inbound requests. Adds correlation IDs, normalizes
 * header names, injects tenant context from auth identity, and rewrites
 * versioned API paths.
 */
@FnRoute(path = "/gw/transform", methods = {"POST"})
@FnGroup("api-gateway")
public class RequestTransformFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    // API version path rewrites
    private static final Map<String, String> VERSION_REWRITES = Map.of(
            "/v1/users", "/api/users",
            "/v1/orders", "/api/orders",
            "/v1/products", "/api/products",
            "/v2/users", "/api/v2/users",
            "/v2/orders", "/api/v2/orders"
    );

    // Headers to strip (security-sensitive)
    private static final Set<String> STRIP_HEADERS = Set.of(
            "X-Internal-Secret", "X-Debug-Mode", "X-Raw-Token"
    );

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var authResult = ctx.heap().get("gw:auth-result", Map.class).orElse(Map.of());

        String correlationId = request.header("X-Correlation-ID")
                .orElse(UUID.randomUUID().toString());

        // Build transformed headers
        Map<String, String> transformedHeaders = new LinkedHashMap<>();
        for (var entry : request.headers().entrySet()) {
            if (!STRIP_HEADERS.contains(entry.getKey())) {
                transformedHeaders.put(entry.getKey().toLowerCase(), entry.getValue());
            }
        }

        // Inject gateway headers
        transformedHeaders.put("x-correlation-id", correlationId);
        transformedHeaders.put("x-gateway-timestamp", String.valueOf(System.currentTimeMillis()));
        transformedHeaders.put("x-gateway-version", ctx.revisionId());

        // Inject tenant/identity context from auth
        if (Boolean.TRUE.equals(authResult.get("authenticated"))) {
            transformedHeaders.put("x-authenticated-user", String.valueOf(authResult.get("subject")));
            List<String> roles = (List<String>) authResult.getOrDefault("roles", List.of());
            transformedHeaders.put("x-user-roles", String.join(",", roles));
        }

        // Rewrite versioned API paths
        String originalPath = request.queryParam("targetPath").orElse(request.path());
        String rewrittenPath = VERSION_REWRITES.getOrDefault(originalPath, originalPath);
        boolean pathRewritten = !rewrittenPath.equals(originalPath);

        // Normalize query parameters (trim whitespace, lowercase keys)
        Map<String, String> normalizedParams = new LinkedHashMap<>();
        for (var entry : request.queryParams().entrySet()) {
            normalizedParams.put(entry.getKey().toLowerCase().trim(), entry.getValue().trim());
        }

        // Compute content type and encoding info
        String contentType = transformedHeaders.getOrDefault("content-type", "application/json");
        long bodySize = request.body() != null ? request.body().length : 0;

        Map<String, Object> transformed = new LinkedHashMap<>();
        transformed.put("correlationId", correlationId);
        transformed.put("originalPath", originalPath);
        transformed.put("rewrittenPath", rewrittenPath);
        transformed.put("pathRewritten", pathRewritten);
        transformed.put("method", request.method());
        transformed.put("transformedHeaders", transformedHeaders);
        transformed.put("normalizedParams", normalizedParams);
        transformed.put("contentType", contentType);
        transformed.put("bodySize", bodySize);
        transformed.put("headersStripped", STRIP_HEADERS.stream()
                .filter(h -> request.headers().containsKey(h))
                .toList());

        ctx.heap().publish("gw:transformed-request", transformed, Map.class);
        return KubeFnResponse.ok(transformed);
    }
}

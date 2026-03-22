package com.enterprise.gateway;

import io.kubefn.api.*;

import java.time.Duration;
import java.util.*;

/**
 * Checks for cached responses before forwarding to the backend.
 * Caches GET responses with configurable TTL per path pattern.
 * Supports cache-control header directives.
 */
@FnRoute(path = "/gw/cache", methods = {"POST"})
@FnGroup("api-gateway")
public class ResponseCacheFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    // Cache TTL configuration per path prefix
    private static final Map<String, Duration> CACHE_TTL_CONFIG = Map.of(
            "/api/products", Duration.ofMinutes(5),
            "/api/users", Duration.ofSeconds(30),
            "/api/orders", Duration.ofSeconds(10)
    );

    // Methods that are cacheable
    private static final Set<String> CACHEABLE_METHODS = Set.of("GET", "HEAD");

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var transformed = ctx.heap().get("gw:transformed-request", Map.class).orElse(Map.of());
        String path = (String) transformed.getOrDefault("rewrittenPath", request.path());
        String method = (String) transformed.getOrDefault("method", request.method());
        String correlationId = (String) transformed.getOrDefault("correlationId", "unknown");
        Map<String, String> headers = (Map<String, String>) transformed.getOrDefault(
                "transformedHeaders", Map.of());

        // Check cache-control directives
        String cacheControl = headers.getOrDefault("cache-control", "");
        boolean noCache = cacheControl.contains("no-cache") || cacheControl.contains("no-store");
        boolean isCacheable = CACHEABLE_METHODS.contains(method) && !noCache;

        String cacheKey = "response:" + method + ":" + path + ":" +
                transformed.getOrDefault("normalizedParams", Map.of()).hashCode();

        Map<String, Object> cacheResult = new LinkedHashMap<>();
        cacheResult.put("cacheKey", cacheKey);
        cacheResult.put("cacheable", isCacheable);
        cacheResult.put("correlationId", correlationId);

        if (isCacheable) {
            // Try to retrieve from cache
            var cached = ctx.cache().get(cacheKey, Map.class);
            if (cached.isPresent()) {
                Map<String, Object> cachedResponse = cached.get();
                cacheResult.put("cacheHit", true);
                cacheResult.put("cachedResponse", cachedResponse);
                cacheResult.put("cachedAt", cachedResponse.get("cachedAt"));
                cacheResult.put("ttlSeconds", cachedResponse.get("ttlSeconds"));

                ctx.heap().publish("gw:cache-result", cacheResult, Map.class);
                return KubeFnResponse.ok(cacheResult)
                        .header("X-Cache", "HIT")
                        .header("X-Cache-Key", cacheKey);
            }
        }

        cacheResult.put("cacheHit", false);
        cacheResult.put("reason", noCache ? "no-cache directive" :
                (!CACHEABLE_METHODS.contains(method) ? "non-cacheable method" : "cache miss"));

        // Determine TTL for future caching
        Duration ttl = CACHE_TTL_CONFIG.entrySet().stream()
                .filter(e -> path.startsWith(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(Duration.ofSeconds(60));

        cacheResult.put("ttlSeconds", ttl.toSeconds());

        // Store a simulated response in cache for future hits
        if (isCacheable) {
            Map<String, Object> responseToCache = new LinkedHashMap<>();
            responseToCache.put("status", 200);
            responseToCache.put("body", Map.of("data", "cached_response_for_" + path));
            responseToCache.put("cachedAt", System.currentTimeMillis());
            responseToCache.put("ttlSeconds", ttl.toSeconds());
            ctx.cache().put(cacheKey, responseToCache, ttl);
            cacheResult.put("storedForNextRequest", true);
        }

        ctx.heap().publish("gw:cache-result", cacheResult, Map.class);
        return KubeFnResponse.ok(cacheResult)
                .header("X-Cache", "MISS")
                .header("X-Cache-Key", cacheKey);
    }
}

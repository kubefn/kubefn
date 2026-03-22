package com.enterprise.gateway;

import com.kubefn.api.*;

import java.time.Duration;
import java.util.*;

/**
 * Token-bucket rate limiter. Tracks request counts per client IP using FnCache
 * with TTL-based bucket refills. Returns rate limit headers and rejects
 * requests that exceed the allowed burst.
 */
@FnRoute(path = "/gw/ratelimit", methods = {"POST"})
@FnGroup("api-gateway")
public class RateLimiterFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    private static final int MAX_TOKENS = 100;
    private static final int REFILL_TOKENS = 10;
    private static final Duration WINDOW = Duration.ofSeconds(60);

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        String clientIp = request.header("X-Forwarded-For")
                .or(() -> request.queryParam("clientIp"))
                .orElse("127.0.0.1");

        String bucketKey = "ratelimit:" + clientIp;

        // Retrieve or create token bucket from cache
        Map<String, Object> bucket = ctx.cache().get(bucketKey, Map.class)
                .orElse(null);

        long now = System.currentTimeMillis();
        int tokens;
        long windowStart;

        if (bucket == null) {
            // New bucket
            tokens = MAX_TOKENS - 1;
            windowStart = now;
        } else {
            tokens = ((Number) bucket.get("tokens")).intValue();
            windowStart = ((Number) bucket.get("windowStart")).longValue();

            // Calculate refill based on elapsed time
            long elapsedSeconds = (now - windowStart) / 1000;
            int refillAmount = (int) (elapsedSeconds / 6) * REFILL_TOKENS; // Refill every 6s
            tokens = Math.min(MAX_TOKENS, tokens + refillAmount);

            if (refillAmount > 0) {
                windowStart = now;
            }

            tokens -= 1; // Consume one token for this request
        }

        boolean allowed = tokens >= 0;

        // Update bucket in cache
        Map<String, Object> updatedBucket = new LinkedHashMap<>();
        updatedBucket.put("tokens", Math.max(0, tokens));
        updatedBucket.put("windowStart", windowStart);
        updatedBucket.put("clientIp", clientIp);
        updatedBucket.put("lastAccess", now);
        ctx.cache().put(bucketKey, updatedBucket, WINDOW);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("allowed", allowed);
        result.put("clientIp", clientIp);
        result.put("remainingTokens", Math.max(0, tokens));
        result.put("maxTokens", MAX_TOKENS);
        result.put("retryAfterMs", allowed ? 0 : 6000);

        ctx.heap().publish("gw:rate-limit", result, Map.class);

        if (!allowed) {
            return KubeFnResponse.status(429)
                    .header("X-RateLimit-Remaining", "0")
                    .header("X-RateLimit-Limit", String.valueOf(MAX_TOKENS))
                    .header("Retry-After", "6")
                    .body(Map.of("error", "rate_limit_exceeded",
                            "message", "Too many requests. Retry after 6 seconds.",
                            "clientIp", clientIp));
        }

        return KubeFnResponse.ok(result);
    }
}

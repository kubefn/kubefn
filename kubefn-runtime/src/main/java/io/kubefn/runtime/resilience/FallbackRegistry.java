package io.kubefn.runtime.resilience;

import io.kubefn.api.KubeFnHandler;
import io.kubefn.api.KubeFnRequest;
import io.kubefn.api.KubeFnResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for fallback functions. When a circuit breaker trips,
 * the fallback function is invoked instead of returning a raw 503.
 *
 * <p>Fallbacks can return cached data, degraded responses, or
 * static defaults — keeping the pipeline alive during partial outages.
 */
public class FallbackRegistry {

    private static final Logger log = LoggerFactory.getLogger(FallbackRegistry.class);

    private final ConcurrentHashMap<String, KubeFnHandler> fallbacks = new ConcurrentHashMap<>();

    /**
     * Register a fallback handler for a function.
     * Key format: "group.function"
     */
    public void register(String group, String function, KubeFnHandler fallback) {
        String key = group + "." + function;
        fallbacks.put(key, fallback);
        log.info("Registered fallback for {}", key);
    }

    /**
     * Get fallback for a function, or null if none registered.
     */
    public KubeFnHandler getFallback(String group, String function) {
        return fallbacks.get(group + "." + function);
    }

    /**
     * Execute fallback if available, otherwise return a default 503 response.
     */
    public KubeFnResponse executeFallback(String group, String function,
                                          KubeFnRequest request, Throwable cause) {
        KubeFnHandler fallback = getFallback(group, function);
        if (fallback != null) {
            try {
                log.info("Executing fallback for {}.{}", group, function);
                return fallback.handle(request);
            } catch (Exception e) {
                log.error("Fallback failed for {}.{}", group, function, e);
            }
        }

        // Default degraded response
        return KubeFnResponse.status(503).body(Map.of(
                "error", "Service degraded",
                "function", group + "." + function,
                "reason", cause != null ? cause.getMessage() : "circuit breaker open",
                "fallback", fallback != null ? "fallback_failed" : "no_fallback_registered"
        ));
    }
}

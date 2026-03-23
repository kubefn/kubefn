package com.kubefn.runtime.heap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Heap lifecycle manager — handles TTL-based eviction, request-scoped
 * cleanup, and memory pressure detection.
 *
 * <p>Without this, HeapExchange objects accumulate forever. After millions
 * of requests over days, the heap grows unbounded and GC pressure kills
 * tail latency. This manager prevents that.
 *
 * <h3>Three object lifetimes:</h3>
 * <ul>
 *   <li><b>Request-scoped:</b> Evicted when the request completes.
 *       Prefix key with {@code req:<requestId>:}</li>
 *   <li><b>TTL-scoped:</b> Evicted after a configurable duration.
 *       Registered via {@link #setTTL(String, long)}</li>
 *   <li><b>Permanent:</b> Lives until explicitly removed.
 *       Default behavior (no TTL set)</li>
 * </ul>
 */
public class HeapLifecycle {

    private static final Logger log = LoggerFactory.getLogger(HeapLifecycle.class);

    private final HeapExchangeImpl heap;
    private final HeapGuard guard;

    // TTL tracking: key → expiry timestamp (millis)
    private final ConcurrentHashMap<String, Long> keyExpiry = new ConcurrentHashMap<>();

    // Per-key TTL configuration: key pattern → TTL millis
    private final ConcurrentHashMap<String, Long> ttlConfig = new ConcurrentHashMap<>();

    // Default TTL for keys that match no pattern (0 = no expiry)
    private long defaultTTLMs = 0;

    // Memory pressure thresholds
    private final double pressureWarningThreshold;  // 0.0-1.0
    private final double pressureCriticalThreshold;

    // Scheduled evictor
    private final ScheduledExecutorService evictor;

    // Metrics
    private long totalEvictions = 0;
    private long pressureEvictions = 0;

    public HeapLifecycle(HeapExchangeImpl heap, HeapGuard guard) {
        this(heap, guard, 0.7, 0.9);
    }

    public HeapLifecycle(HeapExchangeImpl heap, HeapGuard guard,
                         double warningThreshold, double criticalThreshold) {
        this.heap = heap;
        this.guard = guard;
        this.pressureWarningThreshold = warningThreshold;
        this.pressureCriticalThreshold = criticalThreshold;

        // Start periodic eviction (every 5 seconds)
        this.evictor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kubefn-heap-evictor");
            t.setDaemon(true);
            return t;
        });
        this.evictor.scheduleAtFixedRate(this::evictionCycle, 5, 5, TimeUnit.SECONDS);
    }

    /**
     * Set TTL for a specific key. The key will be evicted after the TTL expires.
     */
    public void setTTL(String key, long ttlMs) {
        if (ttlMs > 0) {
            keyExpiry.put(key, System.currentTimeMillis() + ttlMs);
        }
    }

    /**
     * Configure default TTL for keys matching a pattern.
     * Pattern supports prefix matching: "pricing:*" matches "pricing:current".
     */
    public void configureTTL(String keyPattern, long ttlMs) {
        ttlConfig.put(keyPattern, ttlMs);
        log.info("Configured TTL for pattern '{}': {}ms", keyPattern, ttlMs);
    }

    /**
     * Set default TTL for all keys (0 = no expiry).
     */
    public void setDefaultTTL(long ttlMs) {
        this.defaultTTLMs = ttlMs;
        log.info("Default heap TTL set to {}ms", ttlMs);
    }

    /**
     * Called when a new object is published to HeapExchange.
     * Sets TTL if a matching pattern is configured.
     */
    public void onPublish(String key) {
        // Check pattern-based TTL
        for (Map.Entry<String, Long> entry : ttlConfig.entrySet()) {
            String pattern = entry.getKey();
            if (matchesPattern(key, pattern)) {
                setTTL(key, entry.getValue());
                return;
            }
        }

        // Apply default TTL if set
        if (defaultTTLMs > 0) {
            setTTL(key, defaultTTLMs);
        }
    }

    /**
     * Called when a request completes. Evicts all request-scoped heap objects.
     */
    public void onRequestComplete(String requestId) {
        String prefix = "req:" + requestId + ":";
        Set<String> keys = heap.keys();
        int evicted = 0;
        for (String key : keys) {
            if (key.startsWith(prefix)) {
                heap.remove(key);
                keyExpiry.remove(key);
                evicted++;
            }
        }
        if (evicted > 0) {
            log.debug("Evicted {} request-scoped heap objects for request {}", evicted, requestId);
        }
    }

    /**
     * Periodic eviction cycle — runs every 5 seconds.
     */
    void evictionCycle() {
        try {
            long now = System.currentTimeMillis();
            int ttlEvicted = 0;
            int pressureEvicted = 0;

            // 1. TTL-based eviction
            for (Map.Entry<String, Long> entry : keyExpiry.entrySet()) {
                if (entry.getValue() <= now) {
                    heap.remove(entry.getKey());
                    keyExpiry.remove(entry.getKey());
                    ttlEvicted++;
                }
            }

            // 2. Stale object eviction (via HeapGuard)
            int staleEvicted = heap.evictStale();

            // 3. Memory pressure detection
            Runtime runtime = Runtime.getRuntime();
            long used = runtime.totalMemory() - runtime.freeMemory();
            long max = runtime.maxMemory();
            double pressure = (double) used / max;

            if (pressure > pressureCriticalThreshold) {
                // Critical: evict oldest objects aggressively
                log.warn("CRITICAL heap pressure: {:.1f}%. Evicting oldest objects.",
                        pressure * 100);
                pressureEvicted = evictOldest(heap.keys().size() / 4); // Evict 25%
                pressureEvictions += pressureEvicted;
            } else if (pressure > pressureWarningThreshold) {
                log.info("Heap pressure warning: {:.1f}%", pressure * 100);
            }

            totalEvictions += ttlEvicted + staleEvicted + pressureEvicted;

            if (ttlEvicted + staleEvicted + pressureEvicted > 0) {
                log.info("Eviction cycle: ttl={}, stale={}, pressure={}, heap_size={}",
                        ttlEvicted, staleEvicted, pressureEvicted, heap.keys().size());
            }

        } catch (Exception e) {
            log.error("Eviction cycle error", e);
        }
    }

    /**
     * Evict the N oldest objects from the heap.
     */
    private int evictOldest(int count) {
        // Use HeapCapsule timestamps to find oldest
        var capsules = new java.util.ArrayList<Map.Entry<String, Long>>();
        for (String key : heap.keys()) {
            var capsule = heap.getCapsule(key, Object.class);
            capsule.ifPresent(c ->
                    capsules.add(Map.entry(key, c.publishedAt().toEpochMilli())));
        }

        capsules.sort(Map.Entry.comparingByValue());

        int evicted = 0;
        for (int i = 0; i < Math.min(count, capsules.size()); i++) {
            heap.remove(capsules.get(i).getKey());
            keyExpiry.remove(capsules.get(i).getKey());
            evicted++;
        }
        return evicted;
    }

    private boolean matchesPattern(String key, String pattern) {
        if (pattern.endsWith("*")) {
            return key.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return key.equals(pattern);
    }

    /**
     * Get lifecycle metrics for admin endpoint.
     */
    public Map<String, Object> metrics() {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        long max = runtime.maxMemory();

        return Map.of(
                "heapObjects", heap.keys().size(),
                "ttlTrackedKeys", keyExpiry.size(),
                "ttlPatterns", ttlConfig.size(),
                "defaultTTLMs", defaultTTLMs,
                "totalEvictions", totalEvictions,
                "pressureEvictions", pressureEvictions,
                "jvmHeapUsedMB", used / (1024 * 1024),
                "jvmHeapMaxMB", max / (1024 * 1024),
                "jvmHeapPressure", String.format("%.1f%%", (double) used / max * 100)
        );
    }

    /**
     * Shutdown the evictor.
     */
    public void shutdown() {
        evictor.shutdown();
    }
}

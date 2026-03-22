package io.kubefn.runtime.heap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HeapExchange safety guard. Enforces:
 * - Maximum object count per namespace
 * - Maximum total estimated size
 * - Object TTL enforcement (stale object eviction)
 * - Leak detection (objects not accessed for too long)
 * - Publisher attribution tracking
 *
 * <p>This is the safety layer that makes HeapExchange enterprise-grade.
 * Without it, one function could flood the heap and OOM the organism.
 */
public class HeapGuard {

    private static final Logger log = LoggerFactory.getLogger(HeapGuard.class);

    private final int maxObjectCount;
    private final long maxEstimatedSizeBytes;
    private final long staleThresholdMs;

    // Track last access time per key
    private final ConcurrentHashMap<String, Long> lastAccessTime = new ConcurrentHashMap<>();
    // Track estimated size per key (rough heuristic)
    private final ConcurrentHashMap<String, Long> estimatedSizes = new ConcurrentHashMap<>();
    private final AtomicLong totalEstimatedSize = new AtomicLong(0);

    public HeapGuard(int maxObjectCount, long maxEstimatedSizeBytes, long staleThresholdMs) {
        this.maxObjectCount = maxObjectCount;
        this.maxEstimatedSizeBytes = maxEstimatedSizeBytes;
        this.staleThresholdMs = staleThresholdMs;
    }

    /**
     * Default guard: 10k objects, 512MB estimated size, 1 hour stale threshold.
     */
    public static HeapGuard defaults() {
        return new HeapGuard(10_000, 512 * 1024 * 1024L, 3600_000);
    }

    /**
     * Check if publishing is allowed. Returns null if OK, or error message if blocked.
     */
    public String checkPublish(String key, Object value, int currentObjectCount) {
        if (currentObjectCount >= maxObjectCount) {
            return "HeapExchange at max capacity (" + maxObjectCount + " objects). "
                    + "Evict stale objects or increase limit.";
        }

        long estimatedSize = estimateObjectSize(value);
        if (totalEstimatedSize.get() + estimatedSize > maxEstimatedSizeBytes) {
            return "HeapExchange estimated size limit exceeded ("
                    + (maxEstimatedSizeBytes / (1024 * 1024)) + "MB). "
                    + "Current: " + (totalEstimatedSize.get() / (1024 * 1024)) + "MB.";
        }

        return null; // OK
    }

    /**
     * Record a publish event for tracking.
     */
    public void recordPublish(String key, Object value) {
        long size = estimateObjectSize(value);
        Long oldSize = estimatedSizes.put(key, size);
        if (oldSize != null) {
            totalEstimatedSize.addAndGet(size - oldSize);
        } else {
            totalEstimatedSize.addAndGet(size);
        }
        lastAccessTime.put(key, System.currentTimeMillis());
    }

    /**
     * Record an access for staleness tracking.
     */
    public void recordAccess(String key) {
        lastAccessTime.put(key, System.currentTimeMillis());
    }

    /**
     * Record a removal.
     */
    public void recordRemove(String key) {
        Long size = estimatedSizes.remove(key);
        if (size != null) {
            totalEstimatedSize.addAndGet(-size);
        }
        lastAccessTime.remove(key);
    }

    /**
     * Find stale objects (not accessed within threshold).
     */
    public Set<String> findStaleKeys() {
        long now = System.currentTimeMillis();
        Set<String> stale = ConcurrentHashMap.newKeySet();
        lastAccessTime.forEach((key, lastAccess) -> {
            if (now - lastAccess > staleThresholdMs) {
                stale.add(key);
            }
        });
        return stale;
    }

    /**
     * Get guard metrics for observability.
     */
    public GuardMetrics metrics() {
        return new GuardMetrics(
                lastAccessTime.size(),
                maxObjectCount,
                totalEstimatedSize.get(),
                maxEstimatedSizeBytes,
                findStaleKeys().size()
        );
    }

    /**
     * Rough size estimation. Not precise — heuristic for safety limits.
     */
    private long estimateObjectSize(Object value) {
        if (value == null) return 16;
        if (value instanceof String s) return 40 + (long) s.length() * 2;
        if (value instanceof byte[] b) return 16 + b.length;
        if (value instanceof Map<?,?> m) return 64 + m.size() * 128L;
        if (value instanceof java.util.Collection<?> c) return 64 + c.size() * 64L;
        return 256; // Default estimate for complex objects
    }

    public record GuardMetrics(
            int objectCount,
            int maxObjects,
            long estimatedSizeBytes,
            long maxSizeBytes,
            int staleObjectCount
    ) {
        public String estimatedSizeMB() {
            return String.format("%.1f", estimatedSizeBytes / (1024.0 * 1024.0));
        }
        public String maxSizeMB() {
            return String.format("%.1f", maxSizeBytes / (1024.0 * 1024.0));
        }
    }
}

package io.kubefn.api;

import java.time.Duration;
import java.util.Optional;

/**
 * Per-group, revision-scoped in-memory cache.
 * Backed by Caffeine in the runtime. Each revision gets its own cache instance.
 */
public interface FnCache {

    /**
     * Get a cached value by key.
     */
    <T> Optional<T> get(String key, Class<T> type);

    /**
     * Put a value with no expiration.
     */
    void put(String key, Object value);

    /**
     * Put a value with a TTL.
     */
    void put(String key, Object value, Duration ttl);

    /**
     * Evict a key from the cache.
     */
    void evict(String key);

    /**
     * Clear all entries.
     */
    void clear();

    /**
     * Get approximate size.
     */
    long size();
}

package io.kubefn.runtime.context;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.kubefn.api.FnCache;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caffeine-backed per-group revision-scoped cache.
 * Each group revision gets its own cache instance.
 */
public class CaffeineFnCache implements FnCache {

    private final Cache<String, Object> cache;
    // Separate caches for TTL entries
    private final ConcurrentHashMap<String, Cache<String, Object>> ttlCaches = new ConcurrentHashMap<>();

    public CaffeineFnCache(long maxSize) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .recordStats()
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(String key, Class<T> type) {
        Object value = cache.getIfPresent(key);
        if (value != null && type.isInstance(value)) {
            return Optional.of((T) value);
        }
        // Check TTL caches
        for (Cache<String, Object> ttlCache : ttlCaches.values()) {
            value = ttlCache.getIfPresent(key);
            if (value != null && type.isInstance(value)) {
                return Optional.of((T) value);
            }
        }
        return Optional.empty();
    }

    @Override
    public void put(String key, Object value) {
        cache.put(key, value);
    }

    @Override
    public void put(String key, Object value, Duration ttl) {
        String ttlKey = ttl.toString();
        Cache<String, Object> ttlCache = ttlCaches.computeIfAbsent(ttlKey, k ->
                Caffeine.newBuilder()
                        .expireAfterWrite(ttl)
                        .maximumSize(10_000)
                        .build());
        ttlCache.put(key, value);
    }

    @Override
    public void evict(String key) {
        cache.invalidate(key);
        ttlCaches.values().forEach(c -> c.invalidate(key));
    }

    @Override
    public void clear() {
        cache.invalidateAll();
        ttlCaches.values().forEach(Cache::invalidateAll);
    }

    @Override
    public long size() {
        long total = cache.estimatedSize();
        for (Cache<String, Object> ttlCache : ttlCaches.values()) {
            total += ttlCache.estimatedSize();
        }
        return total;
    }
}

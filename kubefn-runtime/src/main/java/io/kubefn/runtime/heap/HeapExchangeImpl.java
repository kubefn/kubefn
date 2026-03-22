package io.kubefn.runtime.heap;

import io.kubefn.api.HeapCapsule;
import io.kubefn.api.HeapExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The Shared Object Graph Fabric — KubeFn's revolutionary zero-copy data plane.
 *
 * <p>This is the implementation of Memory-Continuous Architecture. Objects published
 * here are shared across ALL function groups in the namespace. Consumers receive
 * direct heap references — the SAME Java object, same memory address.
 *
 * <p>Zero serialization. Zero copies. Zero network hops. Just a pointer.
 *
 * <h3>Thread Safety</h3>
 * All operations are thread-safe via ConcurrentHashMap. Published objects should
 * be effectively immutable — the HeapExchange does not enforce immutability but
 * strongly recommends it via documentation and convention.
 *
 * <h3>Revision Scoping</h3>
 * The namespace-wide HeapExchange is shared across all revisions. Functions from
 * different revisions can publish and consume from the same fabric. This is by
 * design — it enables the "parse once, use everywhere" pattern.
 */
public class HeapExchangeImpl implements HeapExchange {

    private static final Logger log = LoggerFactory.getLogger(HeapExchangeImpl.class);

    private final ConcurrentHashMap<String, HeapCapsule<?>> store = new ConcurrentHashMap<>();
    private final AtomicLong versionCounter = new AtomicLong(0);

    // Metrics
    private final AtomicLong publishCount = new AtomicLong(0);
    private final AtomicLong getCount = new AtomicLong(0);
    private final AtomicLong hitCount = new AtomicLong(0);
    private final AtomicLong missCount = new AtomicLong(0);

    // Context for publisher attribution
    private static final ThreadLocal<String> currentGroup = new ThreadLocal<>();
    private static final ThreadLocal<String> currentFunction = new ThreadLocal<>();

    /**
     * Set the current execution context for publisher attribution.
     * Called by the runtime before invoking a function handler.
     */
    public static void setCurrentContext(String group, String function) {
        currentGroup.set(group);
        currentFunction.set(function);
    }

    public static void clearCurrentContext() {
        currentGroup.remove();
        currentFunction.remove();
    }

    @Override
    public <T> HeapCapsule<T> publish(String key, T value, Class<T> type) {
        long version = versionCounter.incrementAndGet();
        HeapCapsule<T> capsule = new HeapCapsule<>(
                key,
                value,                              // Direct reference — THE object, not a copy
                type,
                version,
                currentGroup.get() != null ? currentGroup.get() : "unknown",
                currentFunction.get() != null ? currentFunction.get() : "unknown",
                Instant.now()
        );

        store.put(key, capsule);
        publishCount.incrementAndGet();

        log.debug("HeapExchange: published '{}' (type={}, version={}, publisher={}.{})",
                key, type.getSimpleName(), version,
                capsule.publisherGroup(), capsule.publisherFunction());

        return capsule;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(String key, Class<T> type) {
        getCount.incrementAndGet();
        HeapCapsule<?> capsule = store.get(key);

        if (capsule == null) {
            missCount.incrementAndGet();
            return Optional.empty();
        }

        if (!type.isAssignableFrom(capsule.type())) {
            log.warn("HeapExchange: type mismatch for '{}'. Expected={}, actual={}",
                    key, type.getSimpleName(), capsule.type().getSimpleName());
            missCount.incrementAndGet();
            return Optional.empty();
        }

        hitCount.incrementAndGet();

        // This is the magic: return the SAME object reference.
        // Zero copy. Zero serialization. Same heap address.
        return Optional.of((T) capsule.value());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<HeapCapsule<T>> getCapsule(String key, Class<T> type) {
        getCount.incrementAndGet();
        HeapCapsule<?> capsule = store.get(key);

        if (capsule == null || !type.isAssignableFrom(capsule.type())) {
            missCount.incrementAndGet();
            return Optional.empty();
        }

        hitCount.incrementAndGet();
        return Optional.of((HeapCapsule<T>) capsule);
    }

    @Override
    public boolean remove(String key) {
        HeapCapsule<?> removed = store.remove(key);
        if (removed != null) {
            log.debug("HeapExchange: removed '{}' (was version {})", key, removed.version());
            return true;
        }
        return false;
    }

    @Override
    public Set<String> keys() {
        return Set.copyOf(store.keySet());
    }

    @Override
    public boolean contains(String key) {
        return store.containsKey(key);
    }

    /**
     * Get metrics for admin/observability.
     */
    public HeapMetrics metrics() {
        return new HeapMetrics(
                store.size(),
                publishCount.get(),
                getCount.get(),
                hitCount.get(),
                missCount.get()
        );
    }

    public record HeapMetrics(
            int objectCount,
            long publishCount,
            long getCount,
            long hitCount,
            long missCount
    ) {
        public double hitRate() {
            return getCount == 0 ? 0.0 : (double) hitCount / getCount;
        }
    }
}

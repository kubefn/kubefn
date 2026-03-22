package com.kubefn.api;

import java.util.Optional;
import java.util.Set;

/**
 * The Shared Object Graph Fabric — KubeFn's revolutionary zero-copy data plane.
 *
 * <p>Functions publish and consume typed, immutable heap objects directly.
 * No serialization. No network. Same memory address. This is the foundation
 * of Memory-Continuous Architecture.
 *
 * <p>Objects in the HeapExchange are wrapped in {@link HeapCapsule} — immutable
 * snapshots with version metadata, publisher info, and lifecycle scoping.
 *
 * <h3>Example:</h3>
 * <pre>{@code
 * // Function A: parse once, publish to the fabric
 * ProductCatalog catalog = parseFromJson(payload);
 * ctx.heap().publish("catalog", catalog, ProductCatalog.class);
 *
 * // Function B: read directly — ZERO serialization, same heap object
 * ProductCatalog catalog = ctx.heap().get("catalog", ProductCatalog.class)
 *     .orElseThrow();
 * // This IS the same object in memory. Not a copy. Not deserialized.
 * }</pre>
 */
public interface HeapExchange {

    /**
     * Publish a typed object to the shared heap fabric.
     * The object becomes visible to all functions in the namespace.
     * Objects should be effectively immutable once published.
     *
     * @param key  unique key for this object
     * @param value the object to publish (should be immutable)
     * @param type  the type of the object (for type-safe retrieval)
     * @param <T>   the object type
     * @return a capsule wrapping the published object
     */
    <T> HeapCapsule<T> publish(String key, T value, Class<T> type);

    /**
     * Get a shared object by key. Returns a direct heap reference —
     * zero-copy, zero-serialization.
     *
     * @param key  the key to look up
     * @param type expected type
     * @param <T>  the object type
     * @return the live heap object, or empty if not published
     */
    <T> Optional<T> get(String key, Class<T> type);

    /**
     * Get the full capsule with metadata (version, publisher, timestamp).
     */
    <T> Optional<HeapCapsule<T>> getCapsule(String key, Class<T> type);

    /**
     * Remove an object from the exchange.
     */
    boolean remove(String key);

    /**
     * List all keys currently in the exchange.
     */
    Set<String> keys();

    /**
     * Check if a key exists.
     */
    boolean contains(String key);
}

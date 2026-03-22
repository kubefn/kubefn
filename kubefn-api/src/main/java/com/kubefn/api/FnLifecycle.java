package com.kubefn.api;

/**
 * Optional lifecycle hooks for functions that need initialization or cleanup.
 * Implement alongside {@link KubeFnHandler}.
 *
 * <p>The runtime calls these hooks during the revision state machine:
 * LOADING → {@link #onInit()} → WARMING → ACTIVE → {@link #onDrain()} → DRAINING → {@link #onClose()} → UNLOADED
 */
public interface FnLifecycle {

    /**
     * Called after context injection, before the function receives traffic.
     * Use for warmup, preloading caches, establishing connections.
     */
    default void onInit() throws Exception {}

    /**
     * Called when the function is about to be drained (no new requests).
     * In-flight requests will complete before {@link #onClose()}.
     */
    default void onDrain() throws Exception {}

    /**
     * Called after all in-flight requests complete, before classloader unload.
     * Clean up resources, flush state, close connections.
     */
    default void onClose() throws Exception {}
}

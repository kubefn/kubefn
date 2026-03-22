package io.kubefn.api;

import org.slf4j.Logger;

import java.util.Map;

/**
 * The function's window into the living organism.
 * Provides access to HeapExchange, pipelines, cache, logging, config,
 * and sibling functions within the group.
 */
public interface FnContext {

    /**
     * The Shared Object Graph Fabric — zero-copy heap exchange.
     * Publish and consume typed objects at memory speed.
     */
    HeapExchange heap();

    /**
     * Create a composable in-memory execution pipeline.
     * Chain functions at nanosecond speed.
     */
    FnPipeline pipeline();

    /**
     * Revision-scoped in-memory cache for this group.
     */
    FnCache cache();

    /**
     * Get a sibling function in the same group by class.
     * Returns a direct in-memory reference — no network hop.
     */
    <T extends KubeFnHandler> T getFunction(Class<T> type);

    /**
     * Structured logger for this function.
     */
    Logger logger();

    /**
     * Runtime configuration map (from env vars, ConfigMaps, CRD spec).
     */
    Map<String, String> config();

    /**
     * The name of the current group.
     */
    String groupName();

    /**
     * The revision identifier of the current deployment.
     */
    String revisionId();
}

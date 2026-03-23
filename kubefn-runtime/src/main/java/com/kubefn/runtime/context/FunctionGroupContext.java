package com.kubefn.runtime.context;

import com.kubefn.api.*;
import com.kubefn.runtime.graph.FnGraphEngine;
import com.kubefn.runtime.heap.HeapExchangeImpl;
import com.kubefn.runtime.lifecycle.RevisionContext;
import com.kubefn.runtime.resources.SharedResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The function's window into the living organism.
 * One context per group revision. Provides access to HeapExchange,
 * pipeline builder, cache, sibling functions, shared resources, and config.
 *
 * <p>Now wired to:
 * - SharedResourceManager for database/HTTP client access
 * - RevisionContext for request ID tracking
 */
public class FunctionGroupContext implements FnContext {

    private final String groupName;
    private final String revisionId;
    private final HeapExchangeImpl heapExchange;
    private final CaffeineFnCache cache;
    private final Map<String, String> config;
    private final ConcurrentHashMap<Class<? extends KubeFnHandler>, KubeFnHandler> functionRegistry;
    private final SharedResourceManager resourceManager;

    public FunctionGroupContext(String groupName, String revisionId,
                                HeapExchangeImpl heapExchange,
                                Map<String, String> config,
                                SharedResourceManager resourceManager) {
        this.groupName = groupName;
        this.revisionId = revisionId;
        this.heapExchange = heapExchange;
        this.cache = new CaffeineFnCache(10_000);
        this.config = config;
        this.functionRegistry = new ConcurrentHashMap<>();
        this.resourceManager = resourceManager;
    }

    /**
     * Register a function instance in this context (called by the loader).
     */
    public void registerFunction(Class<? extends KubeFnHandler> type, KubeFnHandler instance) {
        functionRegistry.put(type, instance);
    }

    @Override
    public HeapExchange heap() { return heapExchange; }

    @Override
    public FnPipeline pipeline() { return new FnGraphEngine(functionRegistry); }

    @Override
    public FnCache cache() { return cache; }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends KubeFnHandler> T getFunction(Class<T> type) {
        KubeFnHandler handler = functionRegistry.get(type);
        if (handler == null) {
            throw new IllegalArgumentException(
                    "No function of type " + type.getSimpleName() + " in group " + groupName);
        }
        return (T) handler;
    }

    @Override
    public Logger logger() { return LoggerFactory.getLogger("kubefn." + groupName); }

    @Override
    public Map<String, String> config() { return config; }

    @Override
    public String groupName() { return groupName; }

    @Override
    public String revisionId() { return revisionId; }

    @Override
    public String requestId() {
        var ctx = RevisionContext.current();
        return ctx != null ? ctx.requestId() : "unknown";
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T resource(String name, Class<T> type) {
        if (resourceManager == null) {
            throw new IllegalStateException("SharedResourceManager not configured");
        }
        try {
            var lease = resourceManager.acquire(name, groupName, 5000);
            // Note: In production, the lease should be tracked and closed.
            // For now, we return the resource directly — the pool manages lifecycle.
            return lease.resource(type);
        } catch (SharedResourceManager.ResourceUnavailableException e) {
            throw new IllegalStateException("Resource unavailable: " + name, e);
        }
    }

    public Map<Class<? extends KubeFnHandler>, KubeFnHandler> functionRegistry() {
        return functionRegistry;
    }
}

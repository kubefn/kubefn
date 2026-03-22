package io.kubefn.runtime.context;

import io.kubefn.api.*;
import io.kubefn.runtime.graph.FnGraphEngine;
import io.kubefn.runtime.heap.HeapExchangeImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The function's window into the living organism.
 * One context per group revision. Provides access to HeapExchange,
 * pipeline builder, cache, sibling functions, and config.
 */
public class FunctionGroupContext implements FnContext {

    private final String groupName;
    private final String revisionId;
    private final HeapExchangeImpl heapExchange;
    private final CaffeineFnCache cache;
    private final Map<String, String> config;
    private final ConcurrentHashMap<Class<? extends KubeFnHandler>, KubeFnHandler> functionRegistry;

    public FunctionGroupContext(String groupName, String revisionId,
                                HeapExchangeImpl heapExchange,
                                Map<String, String> config) {
        this.groupName = groupName;
        this.revisionId = revisionId;
        this.heapExchange = heapExchange;
        this.cache = new CaffeineFnCache(10_000);
        this.config = config;
        this.functionRegistry = new ConcurrentHashMap<>();
    }

    /**
     * Register a function instance in this context (called by the loader).
     */
    public void registerFunction(Class<? extends KubeFnHandler> type, KubeFnHandler instance) {
        functionRegistry.put(type, instance);
    }

    @Override
    public HeapExchange heap() {
        return heapExchange;
    }

    @Override
    public FnPipeline pipeline() {
        return new FnGraphEngine(functionRegistry);
    }

    @Override
    public FnCache cache() {
        return cache;
    }

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
    public Logger logger() {
        return LoggerFactory.getLogger("kubefn." + groupName);
    }

    @Override
    public Map<String, String> config() {
        return config;
    }

    @Override
    public String groupName() {
        return groupName;
    }

    @Override
    public String revisionId() {
        return revisionId;
    }

    public Map<Class<? extends KubeFnHandler>, KubeFnHandler> functionRegistry() {
        return functionRegistry;
    }
}

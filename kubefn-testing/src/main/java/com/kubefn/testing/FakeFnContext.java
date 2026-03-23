package com.kubefn.testing;

import com.kubefn.api.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A test-friendly FnContext for unit testing KubeFn functions.
 *
 * <pre>{@code
 * var heap = FakeHeapExchange.create()
 *     .with(HeapKeys.PRICING_CURRENT, pricing);
 *
 * var ctx = FakeFnContext.of(heap);
 * var fn = new TaxFunction();
 * fn.setContext(ctx);
 * fn.handle(TestRequest.empty());
 * }</pre>
 */
public final class FakeFnContext implements FnContext {

    private final HeapExchange heap;
    private final String groupName;
    private final String revisionId;
    private final String requestId;
    private final Map<String, String> config;
    private final Map<Class<?>, Object> functions;
    private final Logger logger;

    private FakeFnContext(Builder builder) {
        this.heap = builder.heap;
        this.groupName = builder.groupName;
        this.revisionId = builder.revisionId;
        this.requestId = builder.requestId;
        this.config = Map.copyOf(builder.config);
        this.functions = Map.copyOf(builder.functions);
        this.logger = LoggerFactory.getLogger("kubefn.test." + groupName);
    }

    /** Create a context with just a heap (most common test case). */
    public static FakeFnContext of(HeapExchange heap) {
        return builder().heap(heap).build();
    }

    /** Create a builder for full control. */
    public static Builder builder() {
        return new Builder();
    }

    @Override public HeapExchange heap() { return heap; }
    @Override public FnPipeline pipeline() { return null; }
    @Override public FnCache cache() { return null; }
    @Override public Logger logger() { return logger; }
    @Override public Map<String, String> config() { return config; }
    @Override public String groupName() { return groupName; }
    @Override public String revisionId() { return revisionId; }
    @Override public String requestId() { return requestId; }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends KubeFnHandler> T getFunction(Class<T> type) {
        Object fn = functions.get(type);
        if (fn == null) {
            throw new IllegalStateException(
                    "Function not registered in test context: " + type.getSimpleName() +
                    ". Register with FakeFnContext.builder().registerFunction(instance).build()");
        }
        return (T) fn;
    }

    public static final class Builder {
        private HeapExchange heap = FakeHeapExchange.create();
        private String groupName = "test-group";
        private String revisionId = "test-rev";
        private String requestId = UUID.randomUUID().toString();
        private final Map<String, String> config = new HashMap<>();
        private final Map<Class<?>, Object> functions = new HashMap<>();

        public Builder heap(HeapExchange heap) { this.heap = heap; return this; }
        public Builder groupName(String name) { this.groupName = name; return this; }
        public Builder revisionId(String rev) { this.revisionId = rev; return this; }
        public Builder requestId(String id) { this.requestId = id; return this; }
        public Builder config(String key, String value) { this.config.put(key, value); return this; }

        /** Register a function so ctx.getFunction(Type.class) works in tests. */
        public <T extends KubeFnHandler> Builder registerFunction(T function) {
            this.functions.put(function.getClass(), function);
            return this;
        }

        public FakeFnContext build() {
            return new FakeFnContext(this);
        }
    }
}

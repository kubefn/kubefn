package com.kubefn.shared;

import com.kubefn.api.FnContext;

/**
 * Ergonomic heap publisher — simplifies publishing with validation.
 *
 * <pre>{@code
 * HeapPublisher.publish(ctx, HeapKeys.PRICING_CURRENT, pricingResult);
 * }</pre>
 */
public final class HeapPublisher {

    private HeapPublisher() {}

    /**
     * Publish an object to the heap with automatic type inference.
     */
    @SuppressWarnings("unchecked")
    public static <T> void publish(FnContext ctx, String key, T value) {
        if (value == null) {
            throw new IllegalArgumentException("Cannot publish null to heap key: " + key);
        }
        ctx.heap().publish(key, value, (Class<T>) value.getClass());
        ctx.logger().debug("Published to heap: {} (type={})", key, value.getClass().getSimpleName());
    }

    /**
     * Publish and return the value (for chaining).
     */
    @SuppressWarnings("unchecked")
    public static <T> T publishAndReturn(FnContext ctx, String key, T value) {
        publish(ctx, key, value);
        return value;
    }
}

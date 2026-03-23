package com.kubefn.shared;

import com.kubefn.api.FnContext;
import com.kubefn.api.HeapExchange;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Ergonomic heap reader — makes HeapExchange access easier for developers.
 *
 * <p>Instead of:
 * <pre>{@code
 * PricingResult pricing = ctx.heap().get("pricing:current", PricingResult.class)
 *     .orElseThrow(() -> new IllegalStateException("Pricing not on heap"));
 * }</pre>
 *
 * <p>Write:
 * <pre>{@code
 * PricingResult pricing = HeapReader.require(ctx, "pricing:current", PricingResult.class);
 * // or with default:
 * PricingResult pricing = HeapReader.getOrDefault(ctx, "pricing:current", PricingResult.class,
 *     () -> new PricingResult("USD", 0, 0, 0));
 * }</pre>
 */
public final class HeapReader {

    private HeapReader() {}

    /**
     * Read a required object from the heap. Throws with a clear message if missing.
     */
    public static <T> T require(FnContext ctx, String key, Class<T> type) {
        return ctx.heap().get(key, type)
                .orElseThrow(() -> new IllegalStateException(
                        "Required heap object '" + key + "' (type: " + type.getSimpleName() + ") not found. " +
                        "Ensure the producing function runs before this function."));
    }

    /**
     * Read an optional object with a default value.
     */
    public static <T> T getOrDefault(FnContext ctx, String key, Class<T> type, Supplier<T> defaultValue) {
        return ctx.heap().get(key, type).orElseGet(defaultValue);
    }

    /**
     * Read an optional object, returning null if missing (for Kotlin interop).
     */
    public static <T> T getOrNull(FnContext ctx, String key, Class<T> type) {
        return ctx.heap().get(key, type).orElse(null);
    }

    /**
     * Check if a key exists and log if missing.
     */
    public static boolean isAvailable(FnContext ctx, String key) {
        boolean exists = ctx.heap().contains(key);
        if (!exists) {
            ctx.logger().debug("Heap key '{}' not available yet", key);
        }
        return exists;
    }
}

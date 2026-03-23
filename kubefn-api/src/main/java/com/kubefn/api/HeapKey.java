package com.kubefn.api;

import java.util.Objects;

/**
 * A typed, compile-time-safe key for HeapExchange access.
 *
 * <p>HeapKey eliminates string-based key access and type mismatches.
 * Define keys as static constants in your contracts module:
 *
 * <pre>{@code
 * public final class HeapKeys {
 *     public static final HeapKey<PricingResult> PRICING_CURRENT =
 *         HeapKey.of("pricing:current", PricingResult.class);
 *
 *     public static HeapKey<InventoryStatus> inventory(String sku) {
 *         return HeapKey.of("inventory:" + sku, InventoryStatus.class);
 *     }
 * }
 * }</pre>
 *
 * <p>Then use in functions — compile-time safe, IDE-friendly:
 * <pre>{@code
 * PricingResult pricing = ctx.heap().require(HeapKeys.PRICING_CURRENT);
 * ctx.heap().publish(HeapKeys.TAX_CALCULATED, taxResult);
 * }</pre>
 *
 * @param <T> the type of the heap object
 */
public final class HeapKey<T> {

    private final String name;
    private final Class<T> type;

    private HeapKey(String name, Class<T> type) {
        this.name = Objects.requireNonNull(name, "HeapKey name must not be null");
        this.type = Objects.requireNonNull(type, "HeapKey type must not be null");
    }

    /**
     * Create a typed heap key.
     *
     * @param name the string key (e.g., "pricing:current")
     * @param type the Java class of the value
     * @param <T>  the value type
     * @return a typed HeapKey
     */
    public static <T> HeapKey<T> of(String name, Class<T> type) {
        return new HeapKey<>(name, type);
    }

    /** The string key name. */
    public String name() { return name; }

    /** The value type. */
    public Class<T> type() { return type; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HeapKey<?> that)) return false;
        return name.equals(that.name) && type.equals(that.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type);
    }

    @Override
    public String toString() {
        return "HeapKey[" + name + " : " + type.getSimpleName() + "]";
    }
}

package com.kubefn.contracts;

import com.kubefn.api.HeapKey;

/**
 * Central registry of all HeapExchange keys — typed and compile-time safe.
 *
 * <p>Every heap key used across functions is defined here as a {@link HeapKey}{@code <T>}.
 * This eliminates string typos AND type mismatches at compile time.
 *
 * <h3>Usage in functions:</h3>
 * <pre>{@code
 * // Read — compile-time safe, IDE autocomplete
 * PricingResult pricing = ctx.heap().require(HeapKeys.PRICING_CURRENT);
 *
 * // Write — type-checked, no string keys
 * ctx.heap().publish(HeapKeys.TAX_CALCULATED, taxResult);
 *
 * // Dynamic key with type safety
 * InventoryStatus status = ctx.heap().require(HeapKeys.inventory("SKU-42"));
 * }</pre>
 *
 * <h3>Convention:</h3>
 * <ul>
 *   <li>Static keys: {@code HeapKey.of("domain:identifier", Type.class)}</li>
 *   <li>Dynamic keys: {@code HeapKey.of("domain:" + id, Type.class)} via factory method</li>
 *   <li>NEVER hardcode key strings in functions — always use this class</li>
 * </ul>
 */
public final class HeapKeys {

    private HeapKeys() {}

    // ── Authentication ──────────────────────────────────────────

    /** Auth context for a user. Dynamic key: auth:{userId} */
    public static HeapKey<AuthContext> auth(String userId) {
        return HeapKey.of("auth:" + userId, AuthContext.class);
    }

    // ── Pricing ─────────────────────────────────────────────────

    /** Current pricing result. Published by PricingFunction. */
    public static final HeapKey<PricingResult> PRICING_CURRENT =
            HeapKey.of("pricing:current", PricingResult.class);

    // ── Inventory ───────────────────────────────────────────────

    /** Inventory status for a SKU. Dynamic key: inventory:{sku} */
    public static HeapKey<InventoryStatus> inventory(String sku) {
        return HeapKey.of("inventory:" + sku, InventoryStatus.class);
    }

    // ── Fraud ───────────────────────────────────────────────────

    /** Fraud scoring result. Published by FraudCheckFunction. */
    public static final HeapKey<FraudScore> FRAUD_RESULT =
            HeapKey.of("fraud:result", FraudScore.class);

    // ── Shipping ────────────────────────────────────────────────

    /** Shipping estimate. Published by ShippingFunction. */
    public static final HeapKey<ShippingEstimate> SHIPPING_ESTIMATE =
            HeapKey.of("shipping:estimate", ShippingEstimate.class);

    // ── Tax ─────────────────────────────────────────────────────

    /** Tax calculation. Published by TaxFunction. */
    public static final HeapKey<TaxCalculation> TAX_CALCULATED =
            HeapKey.of("tax:calculated", TaxCalculation.class);

    // ── ML Pipeline ─────────────────────────────────────────────

    /** ML feature vector. Published by feature extraction functions. */
    @SuppressWarnings("unchecked")
    public static final HeapKey<java.util.Map<String, Object>> ML_FEATURES =
            HeapKey.of("ml:features", (Class<java.util.Map<String, Object>>) (Class<?>) java.util.Map.class);

    /** ML prediction result. Published by inference functions. */
    @SuppressWarnings("unchecked")
    public static final HeapKey<java.util.Map<String, Object>> ML_PREDICTION =
            HeapKey.of("ml:prediction", (Class<java.util.Map<String, Object>>) (Class<?>) java.util.Map.class);
}

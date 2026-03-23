package com.example.patterns;

import com.kubefn.api.FnContextAware;
import com.kubefn.api.FnContext;
import com.kubefn.api.KubeFnHandler;
import com.kubefn.api.KubeFnRequest;
import com.kubefn.api.KubeFnResponse;
import com.kubefn.api.FnRoute;
import com.kubefn.api.FnGroup;
import com.kubefn.api.HeapExchange;

import com.kubefn.contracts.AuthContext;
import com.kubefn.contracts.PricingResult;
import com.kubefn.contracts.InventoryStatus;
import com.kubefn.contracts.ShippingEstimate;
import com.kubefn.contracts.TaxCalculation;
import com.kubefn.contracts.HeapKeys;


/**
 * PATTERN 2: CONSUMER
 * ====================
 * A Consumer function reads typed objects from HeapExchange that were
 * published by sibling Producer functions. It assembles a response from
 * multiple heap entries without any serialization overhead.
 *
 * KEY CONCEPT: Zero-copy reads.
 * When you call heap.get(key, Type.class), you receive the EXACT SAME
 * Java object reference that the producer published. There is no JSON
 * deserialization, no protobuf decoding, no copying. This is a direct
 * pointer to the object in shared JVM heap memory.
 *
 * WHEN TO USE THIS PATTERN:
 * - Your function needs data produced by other functions in the same group
 * - You are assembling a composite response from multiple data sources
 * - You want type-safe access to shared data
 *
 * WHAT THIS EXAMPLE DEMONSTRATES:
 * 1. Reading with typed contracts: heap.get(key, Type.class)
 * 2. Reading with static keys (HeapKeys.PRICING_CURRENT)
 * 3. Reading with dynamic keys (HeapKeys.auth(userId))
 * 4. Using .orElseThrow() for REQUIRED data — fail fast if missing
 * 5. Using .orElse(default) for OPTIONAL data — degrade gracefully
 * 6. Assembling a response from multiple heap objects
 */
@FnRoute(path = "/order/summary", methods = {"GET"})
@FnGroup("patterns")
public class OrderSummaryConsumer implements KubeFnHandler, FnContextAware {

    private FnContext ctx;

    @Override
    public void setContext(FnContext context) {
        this.ctx = context;
    }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        HeapExchange heap = ctx.heap();

        ctx.logger().info("OrderSummaryConsumer: assembling order summary from heap");

        // ---------------------------------------------------------------
        // READING REQUIRED DATA — use .orElseThrow()
        // ---------------------------------------------------------------
        // If this data is missing, the function CANNOT produce a valid response.
        // Throwing an exception immediately is better than returning garbage.
        // The caller gets a clear 500 error instead of a silently wrong result.

        // Read PricingResult using a STATIC key.
        // Static keys are used for singleton data — there is only one "current pricing".
        // The second argument (PricingResult.class) ensures type safety at compile time.
        // You get back a PricingResult, not an Object — no casting needed.
        PricingResult pricing = heap.get(HeapKeys.PRICING_CURRENT)
            .orElseThrow(() -> new IllegalStateException(
                "PricingResult not found in heap at key '" + HeapKeys.PRICING_CURRENT + "'. "
                + "Ensure a pricing producer function has run before this consumer."
            ));

        // Read AuthContext using a DYNAMIC key.
        // Dynamic keys are parameterized — HeapKeys.auth(userId) returns "auth:<userId>".
        // This is used when multiple instances of the same type exist (one per user).
        String userId = request.queryParam("userId")
            .orElseThrow(() -> new IllegalArgumentException("userId query parameter is required"));

        AuthContext auth = heap.get(HeapKeys.auth(userId))
            .orElseThrow(() -> new IllegalStateException(
                "AuthContext not found for userId '" + userId + "'. "
                + "Ensure the auth function has run for this user."
            ));

        // Read TaxCalculation — another required field.
        TaxCalculation tax = heap.get(HeapKeys.TAX_CALCULATED)
            .orElseThrow(() -> new IllegalStateException(
                "TaxCalculation not found in heap. Ensure tax function has run."
            ));

        // ---------------------------------------------------------------
        // READING OPTIONAL DATA — use .orElse(default)
        // ---------------------------------------------------------------
        // Some data is nice to have but not essential for the response.
        // Use .orElse() to provide a sensible default when the producer
        // has not yet run or the data has expired.

        // ShippingEstimate might not be available yet if the user hasn't
        // selected a shipping method. We provide a default estimate.
        ShippingEstimate shipping = heap.get(HeapKeys.SHIPPING_ESTIMATE)
            .orElse(new ShippingEstimate(
                "standard",     // method: default to standard shipping
                "unknown",      // fromWarehouse: not yet determined
                5,              // estimatedDays: conservative estimate
                0.0             // cost: free shipping as placeholder
            ));

        // Read inventory using a dynamic key — optional because the SKU
        // might not have inventory data loaded yet.
        String sku = request.queryParam("sku").orElse("DEFAULT-SKU-001");
        InventoryStatus inventory = heap.get(HeapKeys.inventory(sku))
            .orElse(new InventoryStatus(sku, 0, 0, "unknown"));

        // ---------------------------------------------------------------
        // ASSEMBLING THE RESPONSE
        // ---------------------------------------------------------------
        // All data has been read from heap with zero serialization cost.
        // Now we compose the final response. Notice how each field access
        // is type-safe — pricing.finalPrice(), not map.get("finalPrice").

        ctx.logger().info("Assembled order summary for userId={}, finalPrice={}",
            userId, pricing.finalPrice());

        return KubeFnResponse.ok(
            "{\"orderId\": \"ORD-" + System.currentTimeMillis() + "\","
            + "\"userId\": \"" + auth.userId() + "\","
            + "\"authenticated\": " + auth.authenticated() + ","
            + "\"tier\": \"" + auth.tier() + "\","
            + "\"pricing\": {"
            + "  \"currency\": \"" + pricing.currency() + "\","
            + "  \"basePrice\": " + pricing.basePrice() + ","
            + "  \"discount\": " + pricing.discount() + ","
            + "  \"finalPrice\": " + pricing.finalPrice()
            + "},"
            + "\"tax\": {"
            + "  \"subtotal\": " + tax.subtotal() + ","
            + "  \"taxRate\": " + tax.taxRate() + ","
            + "  \"taxAmount\": " + tax.taxAmount() + ","
            + "  \"total\": " + tax.total()
            + "},"
            + "\"shipping\": {"
            + "  \"method\": \"" + shipping.method() + "\","
            + "  \"estimatedDays\": " + shipping.estimatedDays() + ","
            + "  \"cost\": " + shipping.cost()
            + "},"
            + "\"inventory\": {"
            + "  \"sku\": \"" + inventory.sku() + "\","
            + "  \"available\": " + inventory.available() + ","
            + "  \"warehouse\": \"" + inventory.warehouse() + "\""
            + "}}"
        );
    }
}

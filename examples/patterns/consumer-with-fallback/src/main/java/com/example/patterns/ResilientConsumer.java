package com.example.patterns;

import com.kubefn.api.FnContextAware;
import com.kubefn.api.FnContext;
import com.kubefn.api.KubeFnHandler;
import com.kubefn.api.KubeFnRequest;
import com.kubefn.api.KubeFnResponse;
import com.kubefn.api.FnRoute;
import com.kubefn.api.FnGroup;
import com.kubefn.api.HeapExchange;

import com.kubefn.contracts.PricingResult;
import com.kubefn.contracts.InventoryStatus;
import com.kubefn.contracts.ShippingEstimate;
import com.kubefn.contracts.FraudScore;
import com.kubefn.contracts.HeapKeys;

import java.util.Optional;


/**
 * PATTERN 3: CONSUMER WITH FALLBACK
 * ===================================
 * A resilient consumer that gracefully handles missing heap data by providing
 * sensible defaults, logging gaps, and returning degraded but valid responses.
 *
 * KEY CONCEPT: Defensive heap consumption.
 * In a real system, not all producers will always run successfully. Network
 * failures, timeouts, and partial deployments mean your consumer MUST handle
 * the case where expected heap data is absent. This pattern shows how.
 *
 * WHEN TO USE THIS PATTERN:
 * - Your function should return SOMETHING even when upstream data is missing
 * - You are building a user-facing endpoint that should never 500
 * - You want to show partial results rather than no results
 * - You are in an environment where producers deploy independently
 *
 * CONTRAST WITH PATTERN 2 (Consumer):
 * - Pattern 2 uses .orElseThrow() — fail fast, suitable for internal pipelines
 * - Pattern 3 uses .orElse() and .map() — degrade gracefully, suitable for APIs
 *
 * WHAT THIS EXAMPLE DEMONSTRATES:
 * 1. Optional.orElse() with sensible default values
 * 2. Optional.map() for transforming present values
 * 3. heap.contains() for pre-checking data availability
 * 4. Logging when data is missing (observability without crashes)
 * 5. Building a degraded but valid response
 * 6. Including a "dataQuality" section so callers know what's real vs. default
 */
@FnRoute(path = "/resilient/quote", methods = {"GET"})
@FnGroup("patterns")
public class ResilientConsumer implements KubeFnHandler, FnContextAware {

    private FnContext ctx;

    @Override
    public void setContext(FnContext context) {
        this.ctx = context;
    }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        HeapExchange heap = ctx.heap();

        ctx.logger().info("ResilientConsumer: building quote with fallbacks");

        String sku = request.queryParam("sku").orElse("DEFAULT-SKU-001");

        // Track which data sources are real vs. defaulted.
        // This metadata helps the caller (or UI) decide how to present the data.
        // For example, a UI might show "estimated price" instead of "price" when
        // pricing data was defaulted.
        boolean hasPricing = false;
        boolean hasInventory = false;
        boolean hasShipping = false;
        boolean hasFraud = false;

        // ---------------------------------------------------------------
        // FALLBACK STRATEGY 1: orElse() with a sensible default
        // ---------------------------------------------------------------
        // WHY: PricingResult might be missing if the pricing service hasn't
        // run yet, or if it failed. We provide a "list price" default so the
        // customer sees something rather than an error page.
        PricingResult pricing = heap.get(HeapKeys.PRICING_CURRENT, PricingResult.class)
            .orElse(new PricingResult(
                "USD",      // currency: default to USD
                0.0,        // basePrice: zero signals "price unavailable"
                0.0,        // discount: no discount when price is unknown
                0.0         // finalPrice: zero — UI should show "price on request"
            ));

        if (pricing.basePrice() > 0) {
            hasPricing = true;
        } else {
            // WHY we log: If pricing is consistently missing, this log entry
            // will show up in monitoring and alert us to a broken producer.
            // We do NOT throw — the function continues with degraded data.
            ctx.logger().warn("PricingResult missing or empty — using default. "
                + "Check if the pricing producer is deployed and healthy.");
        }

        // ---------------------------------------------------------------
        // FALLBACK STRATEGY 2: contains() check before access
        // ---------------------------------------------------------------
        // WHY: Sometimes you need to take different code paths depending on
        // whether data exists, not just substitute a default value.
        // contains() is a lightweight check that does not deserialize anything.
        InventoryStatus inventory;
        if (heap.contains(HeapKeys.inventory(sku))) {
            // Data exists — read it with confidence.
            inventory = heap.get(HeapKeys.inventory(sku), InventoryStatus.class).get();
            hasInventory = true;
            ctx.logger().info("Inventory found for sku={}: available={}", sku, inventory.available());
        } else {
            // Data missing — provide a conservative default.
            // WHY "available: 0": It is safer to say "out of stock" than to
            // say "in stock" when we don't actually know. Overpromising
            // inventory leads to cancelled orders and angry customers.
            inventory = new InventoryStatus(sku, 0, 0, "unknown");
            ctx.logger().warn("Inventory data missing for sku={}. "
                + "Defaulting to available=0 (conservative). "
                + "Customer will see 'check availability' instead of a count.", sku);
        }

        // ---------------------------------------------------------------
        // FALLBACK STRATEGY 3: map() for transformation
        // ---------------------------------------------------------------
        // WHY: Optional.map() lets you transform the value IF present,
        // and returns Optional.empty() if not. This avoids nested if/else.
        // Here we extract just the cost from ShippingEstimate, or default to 0.
        Optional<ShippingEstimate> shippingOpt = heap.get(HeapKeys.SHIPPING_ESTIMATE, ShippingEstimate.class);

        // map() transforms ShippingEstimate -> String without unwrapping manually.
        // If the Optional is empty, map() returns Optional.empty() — no NPE risk.
        String shippingMethod = shippingOpt
            .map(ShippingEstimate::method)    // Extract method if present
            .orElse("standard");              // Default to standard if absent

        double shippingCost = shippingOpt
            .map(ShippingEstimate::cost)      // Extract cost if present
            .orElse(0.0);                     // Free shipping as fallback

        int shippingDays = shippingOpt
            .map(ShippingEstimate::estimatedDays)
            .orElse(7);                       // Conservative 7-day estimate

        if (shippingOpt.isPresent()) {
            hasShipping = true;
        } else {
            ctx.logger().warn("ShippingEstimate missing — using defaults. "
                + "Shipping method='standard', cost=0.0, days=7");
        }

        // ---------------------------------------------------------------
        // FALLBACK STRATEGY 4: Conditional logic based on presence
        // ---------------------------------------------------------------
        // WHY: FraudScore affects whether we show the "buy now" button.
        // If fraud scoring hasn't run, we default to "approved" because
        // blocking ALL purchases when the fraud service is down is worse
        // than allowing a few potentially fraudulent ones through.
        // This is a BUSINESS DECISION — document it clearly.
        FraudScore fraud = heap.get(HeapKeys.FRAUD_RESULT, FraudScore.class)
            .orElse(new FraudScore(
                0.0,        // riskScore: 0 = no risk (optimistic default)
                true,       // approved: allow purchase when fraud service is unavailable
                "fraud-service-unavailable",  // reason: clearly marks this as a default
                "none"      // model: no model was used
            ));

        if (!"none".equals(fraud.model())) {
            hasFraud = true;
        } else {
            // WHY we log at WARN not ERROR: The function is working correctly —
            // it is the upstream fraud service that may be down. WARN signals
            // "something is off but we are handling it" vs. ERROR which signals
            // "this function itself is broken."
            ctx.logger().warn("FraudScore missing — defaulting to approved=true. "
                + "This is a business-accepted fallback. Review if fraud check failures "
                + "are persistent.");
        }

        // ---------------------------------------------------------------
        // ASSEMBLE THE DEGRADED BUT VALID RESPONSE
        // ---------------------------------------------------------------
        // WHY include dataQuality: The caller can use this metadata to:
        // 1. Show "estimated" labels on defaulted fields
        // 2. Trigger a retry to load missing data
        // 3. Log analytics about data availability
        int sourcesAvailable = (hasPricing ? 1 : 0) + (hasInventory ? 1 : 0)
            + (hasShipping ? 1 : 0) + (hasFraud ? 1 : 0);

        ctx.logger().info("Quote assembled: {}/4 data sources available", sourcesAvailable);

        return KubeFnResponse.ok(
            "{\"quote\": {"
            + "  \"sku\": \"" + sku + "\","
            + "  \"pricing\": {"
            + "    \"currency\": \"" + pricing.currency() + "\","
            + "    \"finalPrice\": " + pricing.finalPrice() + ","
            + "    \"estimated\": " + !hasPricing
            + "  },"
            + "  \"inventory\": {"
            + "    \"available\": " + inventory.available() + ","
            + "    \"warehouse\": \"" + inventory.warehouse() + "\","
            + "    \"estimated\": " + !hasInventory
            + "  },"
            + "  \"shipping\": {"
            + "    \"method\": \"" + shippingMethod + "\","
            + "    \"estimatedDays\": " + shippingDays + ","
            + "    \"cost\": " + shippingCost + ","
            + "    \"estimated\": " + !hasShipping
            + "  },"
            + "  \"fraud\": {"
            + "    \"approved\": " + fraud.approved() + ","
            + "    \"riskScore\": " + fraud.riskScore() + ","
            + "    \"estimated\": " + !hasFraud
            + "  }"
            + "},"
            + "\"dataQuality\": {"
            + "  \"sourcesAvailable\": " + sourcesAvailable + ","
            + "  \"sourcesTotal\": 4,"
            + "  \"hasPricing\": " + hasPricing + ","
            + "  \"hasInventory\": " + hasInventory + ","
            + "  \"hasShipping\": " + hasShipping + ","
            + "  \"hasFraud\": " + hasFraud + ","
            + "  \"degraded\": " + (sourcesAvailable < 4)
            + "}}"
        );
    }
}

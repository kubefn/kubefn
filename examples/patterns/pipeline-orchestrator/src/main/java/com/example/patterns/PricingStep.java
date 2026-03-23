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
import com.kubefn.contracts.HeapKeys;


/**
 * PIPELINE STEP 2: Pricing
 * =========================
 * Calculates product pricing and publishes a PricingResult to HeapExchange.
 *
 * NOTE: This step is INDEPENDENT of AuthStep — it does not read AuthContext.
 * In a more advanced pipeline, pricing might read auth.tier() to apply
 * tier-specific discounts. That would make it DEPENDENT on AuthStep,
 * meaning the orchestrator must call AuthStep first. See TaxStep and
 * FraudStep for examples of dependent steps.
 */
@FnRoute(path = "/checkout/pricing", methods = {"POST"})
@FnGroup("patterns")
public class PricingStep implements KubeFnHandler, FnContextAware {

    private FnContext ctx;

    @Override
    public void setContext(FnContext context) {
        this.ctx = context;
    }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        HeapExchange heap = ctx.heap();

        String currency = request.queryParam("currency").orElse("USD");

        ctx.logger().info("PricingStep: calculating pricing in {}", currency);

        // Simulate pricing calculation.
        // In production, this would involve:
        // - Looking up the base price from a catalog
        // - Applying promotional discounts
        // - Converting currencies
        // - Checking price overrides
        double basePrice = 149.99;
        double discountPercent = 15.0;
        double finalPrice = basePrice * (1 - discountPercent / 100.0);

        PricingResult pricing = new PricingResult(
            currency,
            basePrice,
            discountPercent,
            finalPrice
        );

        // Publish using the static key PRICING_CURRENT.
        // Other steps (TaxStep, FraudStep) and the orchestrator will read this.
        heap.publish(HeapKeys.PRICING_CURRENT, pricing, PricingResult.class);

        ctx.logger().info("PricingStep: published PricingResult, finalPrice={}", finalPrice);

        return KubeFnResponse.ok("{\"step\": \"pricing\", \"finalPrice\": " + finalPrice + "}");
    }
}

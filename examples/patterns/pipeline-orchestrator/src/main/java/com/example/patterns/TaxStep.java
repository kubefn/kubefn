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
import com.kubefn.contracts.TaxCalculation;
import com.kubefn.contracts.HeapKeys;


/**
 * PIPELINE STEP 3: Tax Calculation
 * ==================================
 * Reads PricingResult from heap, calculates tax, and publishes TaxCalculation.
 *
 * THIS IS A DEPENDENT STEP.
 * It reads PricingResult published by PricingStep. Therefore, the orchestrator
 * MUST call PricingStep BEFORE calling TaxStep. If PricingResult is missing,
 * this step will fail with a clear error message.
 *
 * DATA FLOW:
 *   PricingStep --publishes--> PricingResult --read-by--> TaxStep
 *                                                         |
 *                                               publishes TaxCalculation
 *
 * WHY this step reads from heap instead of receiving data as a parameter:
 * Because the orchestrator does not need to know WHAT data flows between steps.
 * It just calls steps in order. The steps communicate through heap.
 * This means you can add new data dependencies between steps without
 * changing the orchestrator code at all.
 */
@FnRoute(path = "/checkout/tax", methods = {"POST"})
@FnGroup("patterns")
public class TaxStep implements KubeFnHandler, FnContextAware {

    private FnContext ctx;

    @Override
    public void setContext(FnContext context) {
        this.ctx = context;
    }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        HeapExchange heap = ctx.heap();

        ctx.logger().info("TaxStep: reading PricingResult from heap");

        // Read PricingResult from heap — this MUST exist because the
        // orchestrator calls PricingStep before TaxStep.
        // Using .orElseThrow() because tax cannot be calculated without a price.
        PricingResult pricing = heap.get(HeapKeys.PRICING_CURRENT, PricingResult.class)
            .orElseThrow(() -> new IllegalStateException(
                "TaxStep requires PricingResult in heap at key '" + HeapKeys.PRICING_CURRENT + "'. "
                + "Ensure PricingStep runs before TaxStep in the pipeline."
            ));

        // Calculate tax based on the final price.
        // In production, tax rates would come from a tax service based on
        // the customer's jurisdiction, product category, etc.
        double taxRate = 0.08;  // 8% tax rate
        double subtotal = pricing.finalPrice();
        double taxAmount = subtotal * taxRate;
        double total = subtotal + taxAmount;

        TaxCalculation tax = new TaxCalculation(
            subtotal,
            taxRate,
            taxAmount,
            total
        );

        // Publish tax calculation so the orchestrator and other steps can read it.
        heap.publish(HeapKeys.TAX_CALCULATED, tax, TaxCalculation.class);

        ctx.logger().info("TaxStep: published TaxCalculation, total={} (subtotal={} + tax={})",
            total, subtotal, taxAmount);

        return KubeFnResponse.ok("{\"step\": \"tax\", \"total\": " + total + ", \"taxAmount\": " + taxAmount + "}");
    }
}

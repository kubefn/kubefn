package com.enterprise;

import com.kubefn.api.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the full pricing pipeline. Calls all upstream functions,
 * reads their heap results, computes the final optimized price, and
 * produces a detailed price explanation with audit trail.
 */
@FnRoute(path = "/price/calculate", methods = {"GET", "POST"})
@FnGroup("pricing-engine")
public class PriceCalculatorFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        long startNanos = System.nanoTime();

        // Execute pipeline steps
        ctx.getFunction(CatalogLookupFunction.class).handle(request);
        long afterCatalog = System.nanoTime();

        ctx.getFunction(CompetitorPriceFunction.class).handle(request);
        long afterCompetitor = System.nanoTime();

        ctx.getFunction(DemandAnalysisFunction.class).handle(request);
        long afterDemand = System.nanoTime();

        ctx.getFunction(PromotionEngineFunction.class).handle(request);
        long afterPromotions = System.nanoTime();

        // Read all results from heap
        var catalog = ctx.heap().get("pricing:catalog", Map.class).orElse(Map.of());
        var competitor = ctx.heap().get("pricing:competitor", Map.class).orElse(Map.of());
        var demand = ctx.heap().get("pricing:demand", Map.class).orElse(Map.of());
        var promotions = ctx.heap().get("pricing:promotions", Map.class).orElse(Map.of());

        // Extract key values
        double basePrice = ((Number) catalog.getOrDefault("basePrice", 100.0)).doubleValue();
        double costBasis = ((Number) catalog.getOrDefault("costBasis", 40.0)).doubleValue();
        double marginFloor = ((Number) catalog.getOrDefault("marginFloor", 45.0)).doubleValue();
        int quantity = ((Number) catalog.getOrDefault("requestedQuantity", 1)).intValue();
        double volumeDiscountPct = ((Number) catalog.getOrDefault("volumeDiscountPct", 0.0)).doubleValue();
        double tierAdjustment = ((Number) catalog.getOrDefault("tierAdjustment", 0.0)).doubleValue();

        double demandMultiplier = ((Number) demand.getOrDefault("demandMultiplier", 1.0)).doubleValue();
        double competitorAdjustment = ((Number) competitor.getOrDefault("suggestedAdjustment", 0.0)).doubleValue();
        double promoPrice = ((Number) promotions.getOrDefault("priceAfterPromotions", basePrice)).doubleValue();

        // Step 1: Start from base price
        double price = basePrice;
        List<Map<String, Object>> priceSteps = new ArrayList<>();
        priceSteps.add(step("Base catalog price", price, 0));

        // Step 2: Apply demand multiplier
        double demandAdjustedPrice = price * demandMultiplier;
        double demandDelta = demandAdjustedPrice - price;
        price = demandAdjustedPrice;
        priceSteps.add(step("Demand adjustment (x" + round(demandMultiplier) + ")", price, round(demandDelta)));

        // Step 3: Apply competitor-driven adjustment
        if (competitorAdjustment != 0) {
            double competitorDelta = price * competitorAdjustment;
            price += competitorDelta;
            priceSteps.add(step("Competitor positioning (" + round(competitorAdjustment * 100) + "%)", price, round(competitorDelta)));
        }

        // Step 4: Apply tier adjustment
        if (tierAdjustment != 0) {
            double tierDelta = price * tierAdjustment;
            price += tierDelta;
            priceSteps.add(step("Customer tier adjustment (" + round(tierAdjustment * 100) + "%)", price, round(tierDelta)));
        }

        // Step 5: Apply volume discount
        if (volumeDiscountPct > 0) {
            double volDelta = -(price * volumeDiscountPct);
            price += volDelta;
            priceSteps.add(step("Volume discount (" + round(volumeDiscountPct * 100) + "%)", price, round(volDelta)));
        }

        // Step 6: Apply promotional discounts (use the already-computed promo result)
        double promoDiscountAmt = ((Number) promotions.getOrDefault("discountAmount", 0.0)).doubleValue();
        if (promoDiscountAmt > 0) {
            // Scale promo amount relative to current price vs base
            double scaledPromo = promoDiscountAmt * (price / basePrice);
            price -= scaledPromo;
            priceSteps.add(step("Promotional discounts", price, round(-scaledPromo)));
        }

        // Step 7: Enforce margin floor
        boolean marginOverride = false;
        if (price < marginFloor) {
            double override = marginFloor - price;
            price = marginFloor;
            marginOverride = true;
            priceSteps.add(step("Margin floor enforcement", price, round(override)));
        }

        // Final calculations
        double unitPrice = round(price);
        double lineTotal = round(unitPrice * quantity);
        double margin = (unitPrice - costBasis) / unitPrice;
        double savingsFromBase = basePrice - unitPrice;
        double savingsPct = savingsFromBase / basePrice;

        long endNanos = System.nanoTime();
        double totalMs = (endNanos - startNanos) / 1_000_000.0;

        // Assemble comprehensive price response
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sku", catalog.get("sku"));
        response.put("productName", catalog.get("name"));
        response.put("pricing", Map.of(
                "unitPrice", unitPrice,
                "quantity", quantity,
                "lineTotal", lineTotal,
                "currency", "USD"
        ));
        response.put("savings", Map.of(
                "originalPrice", basePrice,
                "savedAmount", round(savingsFromBase),
                "savedPct", round(savingsPct * 100),
                "marginOverrideApplied", marginOverride
        ));
        response.put("margin", Map.of(
                "costBasis", costBasis,
                "marginPct", round(margin * 100),
                "marginFloor", marginFloor,
                "marginSafe", unitPrice >= marginFloor
        ));
        response.put("priceExplanation", priceSteps);
        response.put("signals", Map.of(
                "catalog", Map.of("basePrice", basePrice, "category", catalog.get("category")),
                "demand", Map.of("level", demand.get("demandLevel"), "multiplier", demandMultiplier),
                "competitor", Map.of("position", competitor.get("marketPosition"), "priceIndex", competitor.get("priceIndex")),
                "promotions", Map.of("applied", promotions.get("promotionsApplied"), "totalDiscountPct", promotions.get("totalDiscountPct"))
        ));
        response.put("_meta", Map.of(
                "pipelineSteps", 5,
                "totalTimeMs", String.format("%.3f", totalMs),
                "totalTimeNanos", endNanos - startNanos,
                "stepTimings", Map.of(
                        "catalogMs", formatMs(afterCatalog - startNanos),
                        "competitorMs", formatMs(afterCompetitor - afterCatalog),
                        "demandMs", formatMs(afterDemand - afterCompetitor),
                        "promotionsMs", formatMs(afterPromotions - afterDemand),
                        "calculationMs", formatMs(endNanos - afterPromotions)
                ),
                "heapObjectsUsed", ctx.heap().keys().size(),
                "zeroCopy", true,
                "note", "5 pricing functions composed in-memory. No HTTP calls. No serialization."
        ));

        return KubeFnResponse.ok(response);
    }

    private Map<String, Object> step(String description, double priceAfter, double delta) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("step", description);
        s.put("priceAfter", round(priceAfter));
        s.put("delta", delta);
        return s;
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private String formatMs(long nanos) {
        return String.format("%.3f", nanos / 1_000_000.0);
    }
}

package com.enterprise;

import com.kubefn.api.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies promotional rules, coupons, and loyalty discounts. Evaluates
 * eligibility for active promotions and stacks applicable discounts
 * respecting mutual exclusion and max-discount constraints.
 */
@FnRoute(path = "/price/promotions", methods = {"POST"})
@FnGroup("pricing-engine")
public class PromotionEngineFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var catalog = ctx.heap().get("pricing:catalog", Map.class).orElse(Map.of());
        var demand = ctx.heap().get("pricing:demand", Map.class).orElse(Map.of());

        String sku = (String) catalog.getOrDefault("sku", "SKU-1001");
        String category = (String) catalog.getOrDefault("category", "general");
        double basePrice = ((Number) catalog.getOrDefault("basePrice", 100.0)).doubleValue();
        String customerTier = (String) catalog.getOrDefault("customerTier", "standard");
        int quantity = ((Number) catalog.getOrDefault("requestedQuantity", 1)).intValue();
        double maxDiscount = ((Number) catalog.getOrDefault("maxDiscount", 0.50)).doubleValue();
        String demandLevel = (String) demand.getOrDefault("demandLevel", "normal");

        List<Map<String, Object>> appliedPromotions = new ArrayList<>();
        double totalDiscountPct = 0.0;

        // Promotion 1: Loyalty tier discount
        double loyaltyDiscount = switch (customerTier) {
            case "enterprise" -> 0.10;
            case "premium" -> 0.05;
            case "wholesale" -> 0.08;
            default -> 0.0;
        };
        if (loyaltyDiscount > 0) {
            appliedPromotions.add(promotion("LOYALTY_TIER",
                    "Loyalty discount for " + customerTier + " customers",
                    loyaltyDiscount, "percentage", true));
            totalDiscountPct += loyaltyDiscount;
        }

        // Promotion 2: Category-specific seasonal sale
        double seasonalDiscount = 0.0;
        if ("clearance".equals(demandLevel) || "low".equals(demandLevel)) {
            seasonalDiscount = "clearance".equals(demandLevel) ? 0.20 : 0.10;
            appliedPromotions.add(promotion("SEASONAL_SALE",
                    "Seasonal " + demandLevel + " pricing adjustment",
                    seasonalDiscount, "percentage", true));
            totalDiscountPct += seasonalDiscount;
        }

        // Promotion 3: Bundle discount (quantity-based)
        double bundleDiscount = 0.0;
        if (quantity >= 5) {
            bundleDiscount = Math.min(0.03 * (quantity / 5), 0.15);
            appliedPromotions.add(promotion("BUNDLE_DISCOUNT",
                    "Bundle discount for " + quantity + " units",
                    bundleDiscount, "percentage", true));
            totalDiscountPct += bundleDiscount;
        }

        // Promotion 4: Category flash sale (simulated — based on category + time)
        long hash = Math.abs((sku + category).hashCode());
        boolean flashSaleActive = (hash % 5) == 0; // 20% of products have flash sale
        if (flashSaleActive && !"surge".equals(demandLevel)) {
            double flashDiscount = 0.08;
            appliedPromotions.add(promotion("FLASH_SALE",
                    "Limited-time flash sale on " + category,
                    flashDiscount, "percentage", false));
            // Flash sales are mutually exclusive with seasonal
            if (seasonalDiscount == 0) {
                totalDiscountPct += flashDiscount;
            } else {
                appliedPromotions.get(appliedPromotions.size() - 1).put("applied", false);
                appliedPromotions.get(appliedPromotions.size() - 1).put("reason", "excluded_by_seasonal_sale");
            }
        }

        // Promotion 5: First-purchase bonus (simulated for standard tier)
        if ("standard".equals(customerTier) && (hash % 4) == 0) {
            double firstPurchase = 0.05;
            appliedPromotions.add(promotion("FIRST_PURCHASE",
                    "New customer welcome discount", firstPurchase, "percentage", true));
            totalDiscountPct += firstPurchase;
        }

        // Enforce max discount cap
        boolean discountCapped = totalDiscountPct > maxDiscount;
        double effectiveDiscountPct = Math.min(totalDiscountPct, maxDiscount);

        double discountAmount = basePrice * effectiveDiscountPct;
        double priceAfterPromotions = basePrice - discountAmount;

        // Verify margin floor isn't breached
        double marginFloor = ((Number) catalog.getOrDefault("marginFloor", 0.0)).doubleValue();
        boolean marginSafe = priceAfterPromotions >= marginFloor;
        if (!marginSafe) {
            priceAfterPromotions = marginFloor;
            effectiveDiscountPct = (basePrice - marginFloor) / basePrice;
            discountAmount = basePrice - marginFloor;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sku", sku);
        result.put("promotionsEvaluated", appliedPromotions.size());
        result.put("promotionsApplied", (int) appliedPromotions.stream()
                .filter(p -> Boolean.TRUE.equals(p.get("applied"))).count());
        result.put("appliedPromotions", appliedPromotions);
        result.put("totalDiscountPct", round(effectiveDiscountPct * 100));
        result.put("discountAmount", round(discountAmount));
        result.put("priceAfterPromotions", round(priceAfterPromotions));
        result.put("discountCapped", discountCapped);
        result.put("marginSafe", marginSafe);

        ctx.heap().publish("pricing:promotions", result, Map.class);

        return KubeFnResponse.ok(result);
    }

    private Map<String, Object> promotion(String id, String description,
                                           double value, String type, boolean applied) {
        Map<String, Object> promo = new LinkedHashMap<>();
        promo.put("promotionId", id);
        promo.put("description", description);
        promo.put("value", round(value * 100));
        promo.put("type", type);
        promo.put("applied", applied);
        return promo;
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}

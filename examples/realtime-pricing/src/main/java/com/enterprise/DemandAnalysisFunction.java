package com.enterprise;

import com.kubefn.api.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Analyzes demand and supply signals to compute a demand multiplier for
 * dynamic pricing. Considers inventory levels, seasonal trends, velocity,
 * and real-time demand signals.
 */
@FnRoute(path = "/price/demand", methods = {"POST"})
@FnGroup("pricing-engine")
public class DemandAnalysisFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var catalog = ctx.heap().get("pricing:catalog", Map.class).orElse(Map.of());
        String sku = (String) catalog.getOrDefault("sku", "SKU-1001");
        String category = (String) catalog.getOrDefault("category", "general");
        int requestedQty = ((Number) catalog.getOrDefault("requestedQuantity", 1)).intValue();

        // Simulate inventory and demand signals
        long hash = Math.abs(sku.hashCode());
        int totalInventory = 100 + (int) (hash % 500);
        int reservedUnits = (int) (hash % (totalInventory / 3));
        int availableUnits = totalInventory - reservedUnits;

        // Inventory utilization ratio
        double inventoryUtilization = (double) reservedUnits / totalInventory;

        // Sales velocity (units per day, simulated)
        double dailyVelocity = 5.0 + (hash % 45);
        double daysOfStock = availableUnits / Math.max(dailyVelocity, 0.1);

        // Demand multiplier based on scarcity
        double scarcityMultiplier = 1.0;
        if (daysOfStock < 3) {
            scarcityMultiplier = 1.15; // Scarce: increase price 15%
        } else if (daysOfStock < 7) {
            scarcityMultiplier = 1.08; // Getting low: increase 8%
        } else if (daysOfStock > 60) {
            scarcityMultiplier = 0.90; // Overstocked: decrease 10%
        } else if (daysOfStock > 30) {
            scarcityMultiplier = 0.95; // Excess: decrease 5%
        }

        // Seasonal demand factor
        LocalDateTime now = LocalDateTime.now();
        int month = now.getMonthValue();
        int dayOfWeek = now.getDayOfWeek().getValue(); // 1=Monday, 7=Sunday
        double seasonalFactor = computeSeasonalFactor(category, month);

        // Day-of-week factor (weekends can have different demand)
        double dayOfWeekFactor = (dayOfWeek >= 6) ? 1.05 : 1.0;

        // Time-of-day factor (simulate peak hours)
        int hour = now.getHour();
        double timeOfDayFactor = 1.0;
        if (hour >= 10 && hour <= 14) timeOfDayFactor = 1.03; // Lunch browsing
        if (hour >= 19 && hour <= 22) timeOfDayFactor = 1.05; // Evening shopping

        // Bulk order dampening (large orders get smaller demand premium)
        double bulkDampening = requestedQty > 10 ? 0.97 : 1.0;

        // Combined demand multiplier
        double demandMultiplier = scarcityMultiplier * seasonalFactor
                * dayOfWeekFactor * timeOfDayFactor * bulkDampening;

        // Demand classification
        String demandLevel;
        if (demandMultiplier > 1.15) demandLevel = "surge";
        else if (demandMultiplier > 1.05) demandLevel = "high";
        else if (demandMultiplier >= 0.95) demandLevel = "normal";
        else if (demandMultiplier >= 0.85) demandLevel = "low";
        else demandLevel = "clearance";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sku", sku);
        result.put("inventory", Map.of(
                "total", totalInventory,
                "reserved", reservedUnits,
                "available", availableUnits,
                "utilizationPct", round(inventoryUtilization * 100)
        ));
        result.put("velocity", Map.of(
                "dailySales", round(dailyVelocity),
                "daysOfStock", round(daysOfStock)
        ));
        result.put("demandMultiplier", round(demandMultiplier));
        result.put("demandLevel", demandLevel);
        result.put("factors", Map.of(
                "scarcity", round(scarcityMultiplier),
                "seasonal", round(seasonalFactor),
                "dayOfWeek", round(dayOfWeekFactor),
                "timeOfDay", round(timeOfDayFactor),
                "bulkDampening", round(bulkDampening)
        ));

        ctx.heap().publish("pricing:demand", result, Map.class);

        return KubeFnResponse.ok(result);
    }

    private double computeSeasonalFactor(String category, int month) {
        return switch (category) {
            case "electronics" -> {
                // Black Friday/holiday season boost
                if (month == 11 || month == 12) yield 1.12;
                if (month == 1) yield 0.90; // Post-holiday
                yield 1.0;
            }
            case "apparel" -> {
                // Season changes drive demand
                if (month == 3 || month == 9) yield 1.08; // New season
                if (month == 1 || month == 7) yield 0.85; // Clearance
                yield 1.0;
            }
            case "grocery" -> {
                // Relatively stable, slight holiday bump
                if (month == 11 || month == 12) yield 1.05;
                yield 1.0;
            }
            case "furniture" -> {
                // Back-to-school and new year
                if (month == 8 || month == 1) yield 1.10;
                yield 1.0;
            }
            default -> 1.0;
        };
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}

package com.enterprise;

import io.kubefn.api.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Simulates fetching competitor prices from market intelligence feeds.
 * Computes market position, price index, and competitive pressure metrics.
 */
@FnRoute(path = "/price/competitor", methods = {"POST"})
@FnGroup("pricing-engine")
public class CompetitorPriceFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var catalog = ctx.heap().get("pricing:catalog", Map.class).orElse(Map.of());
        String sku = (String) catalog.getOrDefault("sku", "SKU-1001");
        double basePrice = ((Number) catalog.getOrDefault("basePrice", 100.0)).doubleValue();
        String category = (String) catalog.getOrDefault("category", "general");

        // Simulate competitor prices (deterministic based on SKU)
        List<Map<String, Object>> competitors = generateCompetitorPrices(sku, basePrice, category);

        // Calculate market statistics
        double minCompetitorPrice = competitors.stream()
                .mapToDouble(c -> ((Number) c.get("price")).doubleValue())
                .min().orElse(basePrice);
        double maxCompetitorPrice = competitors.stream()
                .mapToDouble(c -> ((Number) c.get("price")).doubleValue())
                .max().orElse(basePrice);
        double avgCompetitorPrice = competitors.stream()
                .mapToDouble(c -> ((Number) c.get("price")).doubleValue())
                .average().orElse(basePrice);
        double medianPrice = computeMedian(competitors);

        // Price index: our price relative to market average (1.0 = at average)
        double priceIndex = basePrice / avgCompetitorPrice;

        // Market position
        String marketPosition;
        if (priceIndex < 0.90) marketPosition = "price_leader";
        else if (priceIndex < 0.98) marketPosition = "below_average";
        else if (priceIndex <= 1.02) marketPosition = "at_market";
        else if (priceIndex <= 1.10) marketPosition = "above_average";
        else marketPosition = "premium_positioned";

        // Competitive pressure: how tight is the market?
        double priceSpread = (maxCompetitorPrice - minCompetitorPrice) / avgCompetitorPrice;
        String competitivePressure;
        if (priceSpread < 0.10) competitivePressure = "high";
        else if (priceSpread < 0.25) competitivePressure = "moderate";
        else competitivePressure = "low";

        // Suggested competitive adjustment
        double suggestedAdjustment = 0.0;
        if (priceIndex > 1.08 && "high".equals(competitivePressure)) {
            suggestedAdjustment = -0.05; // Reduce by 5% to stay competitive
        } else if (priceIndex < 0.92) {
            suggestedAdjustment = 0.03; // Room to increase by 3%
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sku", sku);
        result.put("competitors", competitors);
        result.put("marketStats", Map.of(
                "minPrice", round(minCompetitorPrice),
                "maxPrice", round(maxCompetitorPrice),
                "avgPrice", round(avgCompetitorPrice),
                "medianPrice", round(medianPrice)
        ));
        result.put("ourBasePrice", basePrice);
        result.put("priceIndex", round(priceIndex));
        result.put("marketPosition", marketPosition);
        result.put("competitivePressure", competitivePressure);
        result.put("priceSpread", round(priceSpread));
        result.put("suggestedAdjustment", suggestedAdjustment);

        ctx.heap().publish("pricing:competitor", result, Map.class);

        return KubeFnResponse.ok(result);
    }

    private List<Map<String, Object>> generateCompetitorPrices(String sku, double basePrice, String category) {
        long hash = Math.abs(sku.hashCode());
        String[][] competitorData = {
                {"CompetitorA", "Amazon", "marketplace"},
                {"CompetitorB", "BestBuy", "retail"},
                {"CompetitorC", "Walmart", "retail"},
                {"CompetitorD", "Newegg", "marketplace"},
                {"CompetitorE", "Target", "retail"}
        };

        List<Map<String, Object>> competitors = new ArrayList<>();
        for (int i = 0; i < competitorData.length; i++) {
            // Generate realistic price variation (-15% to +20% of base)
            double variation = ((hash + i * 7919) % 35 - 15) / 100.0;
            double price = basePrice * (1 + variation);
            boolean inStock = ((hash + i) % 7) != 0; // ~85% in stock
            boolean hasFreeShipping = price > 50 && ((hash + i) % 3) != 0;

            Map<String, Object> comp = new LinkedHashMap<>();
            comp.put("name", competitorData[i][1]);
            comp.put("type", competitorData[i][2]);
            comp.put("price", round(price));
            comp.put("inStock", inStock);
            comp.put("freeShipping", hasFreeShipping);
            comp.put("lastUpdated", System.currentTimeMillis() - ((hash + i) % 3600) * 1000);
            competitors.add(comp);
        }
        return competitors;
    }

    private double computeMedian(List<Map<String, Object>> competitors) {
        double[] prices = competitors.stream()
                .mapToDouble(c -> ((Number) c.get("price")).doubleValue())
                .sorted()
                .toArray();
        if (prices.length == 0) return 0;
        if (prices.length % 2 == 0) {
            return (prices[prices.length / 2 - 1] + prices[prices.length / 2]) / 2.0;
        }
        return prices[prices.length / 2];
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}

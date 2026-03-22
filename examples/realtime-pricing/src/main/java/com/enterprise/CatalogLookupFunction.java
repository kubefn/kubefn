package com.enterprise;

import io.kubefn.api.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads product catalog data including base price, cost, category, attributes,
 * and margin constraints. Publishes enriched catalog entry to HeapExchange.
 */
@FnRoute(path = "/price/catalog", methods = {"POST"})
@FnGroup("pricing-engine")
public class CatalogLookupFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    // Simulated product catalog
    private static final Map<String, Map<String, Object>> CATALOG = Map.ofEntries(
            Map.entry("SKU-1001", catalogEntry("SKU-1001", "Wireless Noise-Cancelling Headphones", "electronics",
                    149.99, 62.00, 0.12, 0.55, List.of("bluetooth", "anc", "premium"), 4.5, 2847)),
            Map.entry("SKU-2001", catalogEntry("SKU-2001", "Organic Cold-Brew Coffee (12-pack)", "grocery",
                    34.99, 14.50, 0.08, 0.45, List.of("organic", "cold-brew", "beverage"), 4.7, 12034)),
            Map.entry("SKU-3001", catalogEntry("SKU-3001", "Ergonomic Standing Desk", "furniture",
                    599.99, 245.00, 0.15, 0.50, List.of("ergonomic", "adjustable", "office"), 4.3, 891)),
            Map.entry("SKU-4001", catalogEntry("SKU-4001", "Running Shoes Pro V3", "apparel",
                    189.99, 48.00, 0.10, 0.60, List.of("running", "performance", "cushion"), 4.6, 5623)),
            Map.entry("SKU-5001", catalogEntry("SKU-5001", "Smart Home Hub Controller", "electronics",
                    79.99, 28.00, 0.10, 0.50, List.of("smart-home", "zigbee", "thread"), 4.1, 3421))
    );

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        String body = request.bodyAsString();
        String productId = extractField(body, "productId", "SKU-1001");
        int quantity = Integer.parseInt(extractField(body, "quantity", "1"));
        String customerTier = extractField(body, "customerTier", "standard");

        Map<String, Object> catalogEntry = CATALOG.getOrDefault(productId, CATALOG.get("SKU-1001"));

        // Enrich with request context
        Map<String, Object> enriched = new LinkedHashMap<>(catalogEntry);
        enriched.put("requestedQuantity", quantity);
        enriched.put("customerTier", customerTier);

        // Calculate volume discount eligibility
        double volumeDiscountPct = 0.0;
        if (quantity >= 100) volumeDiscountPct = 0.15;
        else if (quantity >= 50) volumeDiscountPct = 0.10;
        else if (quantity >= 20) volumeDiscountPct = 0.07;
        else if (quantity >= 10) volumeDiscountPct = 0.04;
        enriched.put("volumeDiscountPct", volumeDiscountPct);

        // Tier-based pricing adjustment
        double tierAdjustment = switch (customerTier) {
            case "enterprise" -> -0.12;
            case "premium" -> -0.08;
            case "wholesale" -> -0.18;
            default -> 0.0;
        };
        enriched.put("tierAdjustment", tierAdjustment);

        // Calculate margin floor
        double basePrice = ((Number) catalogEntry.get("basePrice")).doubleValue();
        double costBasis = ((Number) catalogEntry.get("costBasis")).doubleValue();
        double minMargin = ((Number) catalogEntry.get("minMargin")).doubleValue();
        double marginFloor = costBasis * (1 + minMargin);
        enriched.put("marginFloor", Math.round(marginFloor * 100.0) / 100.0);
        enriched.put("currentMarginPct", Math.round((basePrice - costBasis) / basePrice * 10000.0) / 10000.0);

        ctx.heap().publish("pricing:catalog", enriched, Map.class);

        return KubeFnResponse.ok(enriched);
    }

    private static Map<String, Object> catalogEntry(String sku, String name, String category,
                                                      double basePrice, double costBasis,
                                                      double minMargin, double maxDiscount,
                                                      List<String> tags, double rating, int reviewCount) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("sku", sku);
        entry.put("name", name);
        entry.put("category", category);
        entry.put("basePrice", basePrice);
        entry.put("costBasis", costBasis);
        entry.put("minMargin", minMargin);
        entry.put("maxDiscount", maxDiscount);
        entry.put("tags", tags);
        entry.put("avgRating", rating);
        entry.put("reviewCount", reviewCount);
        return entry;
    }

    private String extractField(String body, String key, String defaultValue) {
        if (body == null || body.isEmpty()) return defaultValue;
        String search = "\"" + key + "\"";
        int idx = body.indexOf(search);
        if (idx < 0) return defaultValue;
        int colonIdx = body.indexOf(':', idx + search.length());
        if (colonIdx < 0) return defaultValue;
        int start = colonIdx + 1;
        while (start < body.length() && (body.charAt(start) == ' ' || body.charAt(start) == '"')) start++;
        int end = start;
        while (end < body.length() && body.charAt(end) != '"' && body.charAt(end) != ','
                && body.charAt(end) != '}') end++;
        return body.substring(start, end).trim();
    }
}

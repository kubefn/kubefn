package com.enterprise.search;

import io.kubefn.api.*;

import java.util.*;

/**
 * Applies user-level personalization to search results. Boosts results based
 * on user's browsing history, purchase patterns, and category preferences.
 */
@FnRoute(path = "/search/personalize", methods = {"POST"})
@FnGroup("search-engine")
public class PersonalizationFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    // Simulated user preference profiles
    private static final Map<String, Map<String, Object>> USER_PROFILES = Map.of(
            "user-101", Map.of(
                    "preferredCategories", List.of("electronics", "peripherals"),
                    "priceAffinity", "mid-range",
                    "brandAffinities", Map.of("UltraBook", 1.3, "SmartPhone", 1.2),
                    "recentViews", List.of("SKU-1001", "SKU-2001", "SKU-1003"),
                    "purchaseHistory", List.of("SKU-4001")
            ),
            "user-202", Map.of(
                    "preferredCategories", List.of("office", "electronics"),
                    "priceAffinity", "budget",
                    "brandAffinities", Map.of("Budget", 1.4),
                    "recentViews", List.of("SKU-1002", "SKU-3002"),
                    "purchaseHistory", List.of("SKU-1004")
            ),
            "user-303", Map.of(
                    "preferredCategories", List.of("electronics"),
                    "priceAffinity", "premium",
                    "brandAffinities", Map.of("Gaming", 1.5, "OLED", 1.3),
                    "recentViews", List.of("SKU-1003", "SKU-3001"),
                    "purchaseHistory", List.of()
            )
    );

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        String userId = request.queryParam("userId").orElse("user-101");

        var indexResults = ctx.heap().get("search:index-results", Map.class)
                .orElse(Map.of());
        List<Map<String, Object>> results = (List<Map<String, Object>>)
                indexResults.getOrDefault("results", List.of());

        Map<String, Object> profile = USER_PROFILES.getOrDefault(userId, Map.of());
        List<String> preferredCategories = (List<String>)
                profile.getOrDefault("preferredCategories", List.of());
        String priceAffinity = (String) profile.getOrDefault("priceAffinity", "mid-range");
        Map<String, Double> brandAffinities = (Map<String, Double>)
                profile.getOrDefault("brandAffinities", Map.of());
        List<String> recentViews = (List<String>)
                profile.getOrDefault("recentViews", List.of());
        List<String> purchaseHistory = (List<String>)
                profile.getOrDefault("purchaseHistory", List.of());

        List<Map<String, Object>> personalized = new ArrayList<>();
        for (Map<String, Object> result : results) {
            Map<String, Object> enhanced = new LinkedHashMap<>(result);
            double baseScore = ((Number) result.getOrDefault("boostedScore",
                    result.getOrDefault("rawScore", 0.0))).doubleValue();

            double personalBoost = 1.0;
            List<String> boostReasons = new ArrayList<>();

            // Category affinity boost
            String category = (String) result.getOrDefault("category", "");
            if (preferredCategories.contains(category)) {
                personalBoost *= 1.2;
                boostReasons.add("category_preference(+" + category + ")");
            }

            // Brand affinity boost
            String title = (String) result.getOrDefault("title", "");
            for (var entry : brandAffinities.entrySet()) {
                if (title.contains(entry.getKey())) {
                    personalBoost *= entry.getValue();
                    boostReasons.add("brand_affinity(+" + entry.getKey() + ")");
                }
            }

            // Price affinity scoring
            double price = ((Number) result.getOrDefault("price", 0.0)).doubleValue();
            switch (priceAffinity) {
                case "budget" -> {
                    if (price < 500) { personalBoost *= 1.3; boostReasons.add("price_affinity(budget)"); }
                    else if (price > 1500) { personalBoost *= 0.7; boostReasons.add("price_penalty(premium)"); }
                }
                case "mid-range" -> {
                    if (price >= 300 && price <= 1500) { personalBoost *= 1.15; boostReasons.add("price_affinity(midrange)"); }
                }
                case "premium" -> {
                    if (price > 1000) { personalBoost *= 1.3; boostReasons.add("price_affinity(premium)"); }
                    else if (price < 200) { personalBoost *= 0.6; boostReasons.add("price_penalty(budget)"); }
                }
            }

            // Recently viewed items get a small boost (familiarity)
            String docId = (String) result.getOrDefault("docId", "");
            if (recentViews.contains(docId)) {
                personalBoost *= 1.1;
                boostReasons.add("recently_viewed");
            }

            // Penalize already purchased (avoid repetition)
            if (purchaseHistory.contains(docId)) {
                personalBoost *= 0.4;
                boostReasons.add("already_purchased(penalty)");
            }

            double personalizedScore = baseScore * personalBoost;
            enhanced.put("personalizedScore", Math.round(personalizedScore * 1000.0) / 1000.0);
            enhanced.put("personalBoostFactor", Math.round(personalBoost * 1000.0) / 1000.0);
            enhanced.put("boostReasons", boostReasons);

            personalized.add(enhanced);
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("userId", userId);
        output.put("results", personalized);
        output.put("profileUsed", !profile.isEmpty());
        output.put("totalResults", personalized.size());

        ctx.heap().publish("search:personalized-results", output, Map.class);
        return KubeFnResponse.ok(output);
    }
}

package com.enterprise.search;

import com.kubefn.api.*;

import java.util.*;

/**
 * Final ranking stage. Combines personalization scores with freshness,
 * popularity, and quality signals. Applies diversity rules to avoid
 * showing too many results from the same category.
 */
@FnRoute(path = "/search/rank", methods = {"POST"})
@FnGroup("search-engine")
public class RankingFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    // Simulated popularity and quality signals per SKU
    private static final Map<String, Map<String, Double>> QUALITY_SIGNALS = Map.ofEntries(
            Map.entry("SKU-1001", Map.of("popularity", 0.85, "reviewScore", 4.6, "conversionRate", 0.12)),
            Map.entry("SKU-1002", Map.of("popularity", 0.72, "reviewScore", 4.1, "conversionRate", 0.18)),
            Map.entry("SKU-1003", Map.of("popularity", 0.91, "reviewScore", 4.8, "conversionRate", 0.09)),
            Map.entry("SKU-2001", Map.of("popularity", 0.95, "reviewScore", 4.7, "conversionRate", 0.15)),
            Map.entry("SKU-2002", Map.of("popularity", 0.65, "reviewScore", 3.9, "conversionRate", 0.22)),
            Map.entry("SKU-3001", Map.of("popularity", 0.88, "reviewScore", 4.5, "conversionRate", 0.08)),
            Map.entry("SKU-3002", Map.of("popularity", 0.60, "reviewScore", 3.7, "conversionRate", 0.20)),
            Map.entry("SKU-4001", Map.of("popularity", 0.78, "reviewScore", 4.3, "conversionRate", 0.25)),
            Map.entry("SKU-1004", Map.of("popularity", 0.40, "reviewScore", 4.0, "conversionRate", 0.30))
    );

    private static final int MAX_SAME_CATEGORY = 3;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var personalized = ctx.heap().get("search:personalized-results", Map.class)
                .orElse(Map.of());
        List<Map<String, Object>> results = (List<Map<String, Object>>)
                personalized.getOrDefault("results", List.of());

        int limit = Integer.parseInt(request.queryParam("limit").orElse("10"));

        // Compute final ranking score
        List<Map<String, Object>> scored = new ArrayList<>();
        for (Map<String, Object> result : results) {
            Map<String, Object> ranked = new LinkedHashMap<>(result);
            String docId = (String) result.getOrDefault("docId", "");
            double personalizedScore = ((Number) result.getOrDefault("personalizedScore",
                    result.getOrDefault("rawScore", 0.0))).doubleValue();

            Map<String, Double> signals = QUALITY_SIGNALS.getOrDefault(docId, Map.of());
            double popularity = signals.getOrDefault("popularity", 0.5);
            double reviewScore = signals.getOrDefault("reviewScore", 3.5);
            double conversionRate = signals.getOrDefault("conversionRate", 0.10);

            // Weighted ranking formula
            double finalScore =
                    personalizedScore * 0.40 +
                    (popularity * 10) * 0.20 +
                    (reviewScore * 2) * 0.15 +
                    (conversionRate * 50) * 0.15 +
                    (Math.random() * 0.5) * 0.10; // Small exploration factor

            ranked.put("finalScore", Math.round(finalScore * 1000.0) / 1000.0);
            ranked.put("signals", Map.of(
                    "popularity", popularity,
                    "reviewScore", reviewScore,
                    "conversionRate", conversionRate
            ));

            scored.add(ranked);
        }

        // Sort by final score descending
        scored.sort((a, b) -> Double.compare(
                ((Number) b.get("finalScore")).doubleValue(),
                ((Number) a.get("finalScore")).doubleValue()));

        // Apply diversity rules: cap same-category results
        List<Map<String, Object>> diversified = new ArrayList<>();
        Map<String, Integer> categoryCount = new HashMap<>();
        List<String> demotedDocs = new ArrayList<>();

        for (Map<String, Object> doc : scored) {
            String category = (String) doc.getOrDefault("category", "unknown");
            int count = categoryCount.getOrDefault(category, 0);
            if (count >= MAX_SAME_CATEGORY) {
                demotedDocs.add((String) doc.get("docId"));
                continue;
            }
            categoryCount.put(category, count + 1);
            doc.put("rank", diversified.size() + 1);
            diversified.add(doc);
            if (diversified.size() >= limit) break;
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("rankedResults", diversified);
        output.put("totalRanked", diversified.size());
        output.put("demotedForDiversity", demotedDocs);
        output.put("diversityApplied", !demotedDocs.isEmpty());
        output.put("rankingWeights", Map.of(
                "personalization", 0.40,
                "popularity", 0.20,
                "reviewQuality", 0.15,
                "conversionRate", 0.15,
                "exploration", 0.10
        ));

        ctx.heap().publish("search:ranked-results", output, Map.class);
        return KubeFnResponse.ok(output);
    }
}

package com.enterprise.search;

import io.kubefn.api.*;

import java.util.*;

/**
 * Simulates an inverted index search. Looks up each token against a mock product
 * index and computes term-frequency scores for matching documents.
 */
@FnRoute(path = "/search/lookup", methods = {"POST"})
@FnGroup("search-engine")
public class IndexLookupFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    // Simulated inverted index: term -> list of (docId, termFrequency)
    private static final Map<String, List<Map<String, Object>>> INVERTED_INDEX;

    static {
        INVERTED_INDEX = new HashMap<>();

        addToIndex("laptop", "SKU-1001", 5, "UltraBook Pro 15\" Laptop", "electronics", 1299.99);
        addToIndex("laptop", "SKU-1002", 3, "Budget Laptop 14\"", "electronics", 449.99);
        addToIndex("laptop", "SKU-1003", 4, "Gaming Laptop RTX 4080", "electronics", 2199.99);
        addToIndex("notebook", "SKU-1001", 2, "UltraBook Pro 15\" Laptop", "electronics", 1299.99);
        addToIndex("notebook", "SKU-1004", 6, "Spiral Notebook 200pg", "office", 12.99);
        addToIndex("phone", "SKU-2001", 7, "SmartPhone X Pro Max", "electronics", 999.99);
        addToIndex("phone", "SKU-2002", 4, "Budget Phone SE", "electronics", 299.99);
        addToIndex("smartphone", "SKU-2001", 5, "SmartPhone X Pro Max", "electronics", 999.99);
        addToIndex("mobile", "SKU-2001", 3, "SmartPhone X Pro Max", "electronics", 999.99);
        addToIndex("tv", "SKU-3001", 6, "4K OLED Smart TV 65\"", "electronics", 1799.99);
        addToIndex("tv", "SKU-3002", 4, "Budget LED TV 50\"", "electronics", 399.99);
        addToIndex("television", "SKU-3001", 3, "4K OLED Smart TV 65\"", "electronics", 1799.99);
        addToIndex("gaming", "SKU-1003", 8, "Gaming Laptop RTX 4080", "electronics", 2199.99);
        addToIndex("gaming", "SKU-4001", 6, "Gaming Mouse RGB", "peripherals", 79.99);
        addToIndex("budget", "SKU-1002", 3, "Budget Laptop 14\"", "electronics", 449.99);
        addToIndex("budget", "SKU-2002", 3, "Budget Phone SE", "electronics", 299.99);
        addToIndex("budget", "SKU-3002", 3, "Budget LED TV 50\"", "electronics", 399.99);
        addToIndex("affordable", "SKU-1002", 2, "Budget Laptop 14\"", "electronics", 449.99);
        addToIndex("affordable", "SKU-2002", 2, "Budget Phone SE", "electronics", 299.99);
        addToIndex("pro", "SKU-1001", 4, "UltraBook Pro 15\" Laptop", "electronics", 1299.99);
        addToIndex("pro", "SKU-2001", 4, "SmartPhone X Pro Max", "electronics", 999.99);
        addToIndex("fast", "SKU-1003", 3, "Gaming Laptop RTX 4080", "electronics", 2199.99);
        addToIndex("quick", "SKU-1003", 2, "Gaming Laptop RTX 4080", "electronics", 2199.99);
    }

    private static void addToIndex(String term, String docId, int tf, String title, String category, double price) {
        INVERTED_INDEX.computeIfAbsent(term, k -> new ArrayList<>()).add(Map.of(
                "docId", docId,
                "termFrequency", tf,
                "title", title,
                "category", category,
                "price", price
        ));
    }

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var parsed = ctx.heap().get("search:parsed-query", Map.class)
                .orElse(Map.of());

        List<String> expandedTerms = (List<String>) parsed.getOrDefault("expandedTerms", List.of());
        Map<String, Object> priceFilter = (Map<String, Object>) parsed.get("priceFilter");

        // Aggregate scores per document across all matching terms
        Map<String, Map<String, Object>> docScores = new LinkedHashMap<>();
        int totalPostingsScanned = 0;

        for (String term : expandedTerms) {
            List<Map<String, Object>> postings = INVERTED_INDEX.getOrDefault(term, List.of());
            totalPostingsScanned += postings.size();

            for (Map<String, Object> posting : postings) {
                String docId = (String) posting.get("docId");
                int tf = (int) posting.get("termFrequency");
                double price = (double) posting.get("price");

                // Apply price filter early (index-level pruning)
                if (priceFilter != null) {
                    double minPrice = ((Number) priceFilter.get("min")).doubleValue();
                    double maxPrice = ((Number) priceFilter.get("max")).doubleValue();
                    if (price < minPrice || price > maxPrice) continue;
                }

                Map<String, Object> existing = docScores.get(docId);
                if (existing == null) {
                    Map<String, Object> doc = new LinkedHashMap<>();
                    doc.put("docId", docId);
                    doc.put("title", posting.get("title"));
                    doc.put("category", posting.get("category"));
                    doc.put("price", price);
                    doc.put("rawScore", (double) tf);
                    doc.put("matchedTerms", new ArrayList<>(List.of(term)));
                    doc.put("termCount", 1);
                    docScores.put(docId, doc);
                } else {
                    existing.put("rawScore", ((Number) existing.get("rawScore")).doubleValue() + tf);
                    ((List<String>) existing.get("matchedTerms")).add(term);
                    existing.put("termCount", (int) existing.get("termCount") + 1);
                }
            }
        }

        // Boost documents that matched more distinct query terms
        List<Map<String, Object>> results = new ArrayList<>(docScores.values());
        for (Map<String, Object> doc : results) {
            int termCount = (int) doc.get("termCount");
            double rawScore = ((Number) doc.get("rawScore")).doubleValue();
            double multiTermBoost = 1.0 + (termCount - 1) * 0.25;
            doc.put("boostedScore", rawScore * multiTermBoost);
        }

        Map<String, Object> indexResult = new LinkedHashMap<>();
        indexResult.put("results", results);
        indexResult.put("totalHits", results.size());
        indexResult.put("postingsScanned", totalPostingsScanned);
        indexResult.put("termsSearched", expandedTerms.size());
        indexResult.put("priceFiltered", priceFilter != null);

        ctx.heap().publish("search:index-results", indexResult, Map.class);
        return KubeFnResponse.ok(indexResult);
    }
}

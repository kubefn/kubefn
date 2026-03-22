package com.enterprise;

import com.kubefn.api.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts structured features from the hydrated context to guide
 * embedding generation and retrieval. Produces query expansion terms,
 * keyword extraction, and contextual metadata for the retrieval step.
 */
@FnRoute(path = "/ai/features", methods = {"POST"})
@FnGroup("ai-pipeline")
public class FeatureExtractionFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    // Domain-specific keyword synonyms for query expansion
    private static final Map<String, List<String>> SYNONYM_MAP = Map.of(
            "scaling", List.of("auto-scaling", "horizontal scaling", "replica management", "HPA"),
            "caching", List.of("cache", "redis", "memcached", "TTL", "eviction"),
            "deploy", List.of("deployment", "rollout", "release", "CI/CD"),
            "monitor", List.of("monitoring", "observability", "metrics", "alerting", "Prometheus"),
            "security", List.of("authentication", "authorization", "RBAC", "TLS", "encryption"),
            "database", List.of("persistence", "storage", "PostgreSQL", "MySQL", "migration"),
            "network", List.of("networking", "ingress", "service mesh", "load balancer"),
            "config", List.of("configuration", "ConfigMap", "secrets", "environment variables")
    );

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var context = ctx.heap().get("ai:context", Map.class).orElse(Map.of());
        String query = (String) context.getOrDefault("query", "");
        String intent = (String) context.getOrDefault("queryIntent", "general");
        List<Map<String, String>> history = (List<Map<String, String>>)
                context.getOrDefault("conversationHistory", List.of());

        // Extract keywords from query
        List<String> keywords = extractKeywords(query);

        // Expand query with domain synonyms
        List<String> expandedTerms = new ArrayList<>(keywords);
        for (String keyword : keywords) {
            String lower = keyword.toLowerCase();
            for (var entry : SYNONYM_MAP.entrySet()) {
                if (lower.contains(entry.getKey())) {
                    expandedTerms.addAll(entry.getValue());
                }
            }
        }

        // Extract context from conversation history
        List<String> historyTopics = new ArrayList<>();
        for (var turn : history) {
            String content = turn.getOrDefault("content", "");
            historyTopics.addAll(extractKeywords(content));
        }

        // Build weighted search terms (current query terms weighted higher)
        List<Map<String, Object>> weightedTerms = new ArrayList<>();
        for (String term : expandedTerms) {
            double weight = keywords.contains(term) ? 1.0 : 0.6;
            weightedTerms.add(Map.of("term", term, "weight", weight, "source", "query"));
        }
        for (String topic : historyTopics) {
            if (!expandedTerms.contains(topic)) {
                weightedTerms.add(Map.of("term", topic, "weight", 0.3, "source", "history"));
            }
        }

        // Determine optimal retrieval parameters based on intent
        int topK = determineTopK(intent);
        double similarityThreshold = determineSimilarityThreshold(intent);

        // Compute query complexity score
        int wordCount = query.split("\\s+").length;
        double complexityScore = Math.min(1.0, wordCount / 20.0)
                * (expandedTerms.size() > 5 ? 1.2 : 1.0);
        complexityScore = Math.min(complexityScore, 1.0);

        Map<String, Object> features = new LinkedHashMap<>();
        features.put("originalQuery", query);
        features.put("keywords", keywords);
        features.put("expandedTerms", expandedTerms);
        features.put("weightedTerms", weightedTerms);
        features.put("historyTopics", historyTopics);
        features.put("queryIntent", intent);
        features.put("queryComplexity", Math.round(complexityScore * 100.0) / 100.0);
        features.put("wordCount", wordCount);
        features.put("retrievalTopK", topK);
        features.put("similarityThreshold", similarityThreshold);

        ctx.heap().publish("ai:features", features, Map.class);

        return KubeFnResponse.ok(features);
    }

    private List<String> extractKeywords(String text) {
        if (text == null || text.isEmpty()) return List.of();
        // Remove common stop words and extract meaningful terms
        String[] stopWords = {"the", "a", "an", "is", "are", "was", "were", "be", "been",
                "being", "have", "has", "had", "do", "does", "did", "will", "would", "could",
                "should", "may", "might", "shall", "can", "to", "of", "in", "for", "on",
                "with", "at", "by", "from", "as", "into", "through", "during", "before",
                "after", "above", "below", "between", "and", "but", "or", "not", "no",
                "so", "if", "then", "than", "too", "very", "just", "about", "how", "what",
                "which", "who", "when", "where", "why", "i", "me", "my", "it", "its", "this", "that"};
        java.util.Set<String> stopSet = new java.util.HashSet<>(java.util.Arrays.asList(stopWords));

        List<String> keywords = new ArrayList<>();
        for (String word : text.split("[\\s,?.!;:()\\[\\]{}\"']+")) {
            String lower = word.toLowerCase().trim();
            if (lower.length() > 2 && !stopSet.contains(lower)) {
                keywords.add(lower);
            }
        }
        return keywords;
    }

    private int determineTopK(String intent) {
        return switch (intent) {
            case "troubleshooting" -> 10;
            case "comparative" -> 8;
            case "instructional" -> 6;
            case "code_generation" -> 5;
            default -> 5;
        };
    }

    private double determineSimilarityThreshold(String intent) {
        return switch (intent) {
            case "definitional" -> 0.80;
            case "code_generation" -> 0.75;
            case "troubleshooting" -> 0.65;
            default -> 0.70;
        };
    }
}

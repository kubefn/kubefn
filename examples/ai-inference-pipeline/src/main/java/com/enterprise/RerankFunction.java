package com.enterprise;

import io.kubefn.api.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reranks retrieved documents using a cross-encoder scoring simulation.
 * Considers query-document relevance, recency, document quality signals,
 * and diversity to produce the final context window for generation.
 */
@FnRoute(path = "/ai/rerank", methods = {"POST"})
@FnGroup("ai-pipeline")
public class RerankFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var retrieval = ctx.heap().get("ai:retrieval", Map.class).orElse(Map.of());
        var features = ctx.heap().get("ai:features", Map.class).orElse(Map.of());
        var aiContext = ctx.heap().get("ai:context", Map.class).orElse(Map.of());

        List<Map<String, Object>> documents = (List<Map<String, Object>>)
                retrieval.getOrDefault("retrievedDocuments", List.of());
        String query = (String) features.getOrDefault("originalQuery", "");
        String intent = (String) features.getOrDefault("queryIntent", "general");
        List<String> keywords = (List<String>) features.getOrDefault("keywords", List.of());

        List<Map<String, Object>> reranked = new ArrayList<>();
        java.util.Set<String> seenCategories = new java.util.HashSet<>();

        for (var doc : documents) {
            String content = (String) doc.getOrDefault("content", "");
            String category = (String) doc.getOrDefault("category", "general");
            double rrfScore = ((Number) doc.getOrDefault("rrfScore", 0.0)).doubleValue();

            // Cross-encoder relevance (simulated query-document pair scoring)
            double crossEncoderScore = computeCrossEncoderScore(query, content, keywords);

            // Quality signal: content length and specificity
            int contentWords = content.split("\\s+").length;
            double qualityScore = Math.min(contentWords / 40.0, 1.0);

            // Diversity bonus: first doc in each category gets boosted
            double diversityBonus = 0.0;
            if (!seenCategories.contains(category)) {
                diversityBonus = 0.05;
                seenCategories.add(category);
            }

            // Intent-based boost
            double intentBoost = computeIntentBoost(intent, category, content);

            // Combined rerank score
            double rerankScore = (0.40 * crossEncoderScore)
                    + (0.25 * rrfScore * 100) // scale up RRF
                    + (0.15 * qualityScore)
                    + (0.10 * intentBoost)
                    + (0.10 * diversityBonus);

            Map<String, Object> entry = new LinkedHashMap<>(doc);
            entry.put("crossEncoderScore", round(crossEncoderScore));
            entry.put("qualityScore", round(qualityScore));
            entry.put("diversityBonus", round(diversityBonus));
            entry.put("intentBoost", round(intentBoost));
            entry.put("rerankScore", round(rerankScore));
            reranked.add(entry);
        }

        // Sort by rerank score descending
        reranked.sort((a, b) -> Double.compare(
                ((Number) b.get("rerankScore")).doubleValue(),
                ((Number) a.get("rerankScore")).doubleValue()));

        // Select top results for context window (limit token budget)
        int maxContextDocs = 3;
        List<Map<String, Object>> contextWindow = reranked.subList(
                0, Math.min(maxContextDocs, reranked.size()));

        // Build assembled context string for generation
        StringBuilder contextText = new StringBuilder();
        for (int i = 0; i < contextWindow.size(); i++) {
            contextText.append("[").append(i + 1).append("] ");
            contextText.append(contextWindow.get(i).get("content"));
            contextText.append("\n\n");
        }

        // Compute context coverage: how many query keywords are covered
        String contextLower = contextText.toString().toLowerCase();
        long coveredKeywords = keywords.stream()
                .filter(kw -> contextLower.contains(kw.toLowerCase()))
                .count();
        double coverage = keywords.isEmpty() ? 1.0 : (double) coveredKeywords / keywords.size();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rerankedDocuments", reranked);
        result.put("contextWindow", contextWindow);
        result.put("contextText", contextText.toString().trim());
        result.put("contextDocCount", contextWindow.size());
        result.put("totalReranked", reranked.size());
        result.put("keywordCoverage", round(coverage));
        result.put("rerankModel", "cross-encoder-ms-marco-MiniLM-L6");

        ctx.heap().publish("ai:rerank", result, Map.class);

        return KubeFnResponse.ok(result);
    }

    private double computeCrossEncoderScore(String query, String content, List<String> keywords) {
        String contentLower = content.toLowerCase();
        String queryLower = query.toLowerCase();

        // Token overlap scoring
        String[] queryTokens = queryLower.split("\\s+");
        int tokenMatches = 0;
        for (String token : queryTokens) {
            if (token.length() > 2 && contentLower.contains(token)) tokenMatches++;
        }
        double tokenOverlap = queryTokens.length > 0 ? (double) tokenMatches / queryTokens.length : 0;

        // Keyword presence scoring (higher weight)
        int kwMatches = 0;
        for (String kw : keywords) {
            if (contentLower.contains(kw.toLowerCase())) kwMatches++;
        }
        double kwScore = keywords.isEmpty() ? 0.5 : (double) kwMatches / keywords.size();

        // Combine with non-linear scaling
        return Math.min(1.0, (tokenOverlap * 0.4 + kwScore * 0.6) * 1.3);
    }

    private double computeIntentBoost(String intent, String category, String content) {
        String lower = content.toLowerCase();
        return switch (intent) {
            case "troubleshooting" -> lower.contains("error") || lower.contains("fix")
                    || lower.contains("issue") || "reliability".equals(category) ? 0.3 : 0.0;
            case "instructional" -> lower.contains("configure") || lower.contains("set up")
                    || lower.contains("implement") ? 0.3 : 0.0;
            case "code_generation" -> lower.contains("example") || lower.contains("spec")
                    || lower.contains("code") ? 0.3 : 0.0;
            case "comparative" -> lower.contains("vs") || lower.contains("compare")
                    || lower.contains("alternative") ? 0.3 : 0.0;
            default -> 0.0;
        };
    }

    private double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}

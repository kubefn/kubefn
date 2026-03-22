package com.enterprise;

import com.kubefn.api.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Simulates vector embedding generation for the query and expanded terms.
 * Produces a dense embedding vector and sparse keyword vector that the
 * retrieval function uses for hybrid search.
 */
@FnRoute(path = "/ai/embed", methods = {"POST"})
@FnGroup("ai-pipeline")
public class EmbeddingFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    private static final int DENSE_DIMENSION = 384; // Simulated all-MiniLM-L6 dimension
    private static final String MODEL_ID = "all-MiniLM-L6-v2";

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var features = ctx.heap().get("ai:features", Map.class).orElse(Map.of());
        String query = (String) features.getOrDefault("originalQuery", "");
        List<String> expandedTerms = (List<String>) features.getOrDefault("expandedTerms", List.of());
        List<String> keywords = (List<String>) features.getOrDefault("keywords", List.of());

        // Generate dense embedding (simulated — deterministic hash-based vector)
        List<Double> denseEmbedding = generateDenseEmbedding(query);

        // Generate sparse BM25-style keyword vector
        Map<String, Double> sparseVector = generateSparseVector(keywords, expandedTerms);

        // Compute embedding metadata
        double l2Norm = 0.0;
        for (double v : denseEmbedding) l2Norm += v * v;
        l2Norm = Math.sqrt(l2Norm);

        // Normalize the dense vector
        List<Double> normalizedEmbedding = new ArrayList<>();
        for (double v : denseEmbedding) {
            normalizedEmbedding.add(Math.round((v / l2Norm) * 100000.0) / 100000.0);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("modelId", MODEL_ID);
        result.put("dimension", DENSE_DIMENSION);
        // Store first 8 values as sample (full vector would be 384-dim)
        result.put("denseEmbeddingSample", normalizedEmbedding.subList(0, Math.min(8, normalizedEmbedding.size())));
        result.put("denseEmbeddingFull", normalizedEmbedding);
        result.put("sparseVector", sparseVector);
        result.put("sparseTermCount", sparseVector.size());
        result.put("l2Norm", Math.round(l2Norm * 10000.0) / 10000.0);
        result.put("queryTokenCount", query.split("\\s+").length);
        result.put("hybridSearchEnabled", true);
        result.put("denseWeight", 0.7);
        result.put("sparseWeight", 0.3);

        ctx.heap().publish("ai:embedding", result, Map.class);

        return KubeFnResponse.ok(result);
    }

    private List<Double> generateDenseEmbedding(String text) {
        // Deterministic pseudo-embedding from text hash
        List<Double> embedding = new ArrayList<>(DENSE_DIMENSION);
        long seed = text.hashCode();
        for (int i = 0; i < DENSE_DIMENSION; i++) {
            seed = (seed * 6364136223846793005L + 1442695040888963407L);
            double value = ((seed >> 16) & 0xFFFF) / 65536.0 - 0.5;
            embedding.add(Math.round(value * 100000.0) / 100000.0);
        }
        return embedding;
    }

    private Map<String, Double> generateSparseVector(List<String> keywords, List<String> expanded) {
        Map<String, Double> sparse = new LinkedHashMap<>();
        // Primary keywords get higher TF-IDF-like weights
        for (String kw : keywords) {
            double idf = 1.0 + Math.log(100.0 / (1.0 + Math.abs(kw.hashCode() % 50)));
            sparse.put(kw, Math.round(idf * 1000.0) / 1000.0);
        }
        // Expanded terms get lower weights
        for (String term : expanded) {
            if (!sparse.containsKey(term)) {
                double idf = 0.5 + Math.log(100.0 / (1.0 + Math.abs(term.hashCode() % 80)));
                sparse.put(term, Math.round(idf * 0.6 * 1000.0) / 1000.0);
            }
        }
        return sparse;
    }
}

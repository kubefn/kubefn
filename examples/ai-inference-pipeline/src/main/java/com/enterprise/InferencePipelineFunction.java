package com.enterprise;

import io.kubefn.api.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the full RAG (Retrieval-Augmented Generation) pipeline.
 * Calls all upstream functions via ctx.getFunction(), reads heap results,
 * simulates LLM generation, and returns the assembled response with
 * comprehensive timing metadata.
 */
@FnRoute(path = "/ai/infer", methods = {"GET", "POST"})
@FnGroup("ai-pipeline")
public class InferencePipelineFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        long startNanos = System.nanoTime();

        // Step 1: Hydrate user context
        ctx.getFunction(ContextHydrationFunction.class).handle(request);
        long afterHydrate = System.nanoTime();

        // Step 2: Extract features from context + query
        ctx.getFunction(FeatureExtractionFunction.class).handle(request);
        long afterFeatures = System.nanoTime();

        // Step 3: Generate embeddings
        ctx.getFunction(EmbeddingFunction.class).handle(request);
        long afterEmbed = System.nanoTime();

        // Step 4: Retrieve relevant documents
        ctx.getFunction(RetrievalFunction.class).handle(request);
        long afterRetrieval = System.nanoTime();

        // Step 5: Rerank results
        ctx.getFunction(RerankFunction.class).handle(request);
        long afterRerank = System.nanoTime();

        // Read all pipeline results from heap
        var aiContext = ctx.heap().get("ai:context", Map.class).orElse(Map.of());
        var features = ctx.heap().get("ai:features", Map.class).orElse(Map.of());
        var embedding = ctx.heap().get("ai:embedding", Map.class).orElse(Map.of());
        var retrieval = ctx.heap().get("ai:retrieval", Map.class).orElse(Map.of());
        var rerank = ctx.heap().get("ai:rerank", Map.class).orElse(Map.of());

        // Step 6: Simulate LLM generation using context window
        String contextText = (String) rerank.getOrDefault("contextText", "");
        String query = (String) aiContext.getOrDefault("query", "");
        String systemContext = (String) aiContext.getOrDefault("systemContext", "");
        String intent = (String) aiContext.getOrDefault("queryIntent", "general");
        int maxTokens = ((Number) aiContext.getOrDefault("maxTokens", 512)).intValue();
        double temperature = ((Number) aiContext.getOrDefault("temperature", 0.5)).doubleValue();

        String generatedResponse = simulateGeneration(query, contextText, systemContext, intent);
        long afterGeneration = System.nanoTime();

        // Build source citations from context window
        List<Map<String, Object>> contextWindow = (List<Map<String, Object>>)
                rerank.getOrDefault("contextWindow", List.of());
        var citations = new java.util.ArrayList<Map<String, Object>>();
        for (int i = 0; i < contextWindow.size(); i++) {
            var doc = contextWindow.get(i);
            Map<String, Object> citation = new LinkedHashMap<>();
            citation.put("index", i + 1);
            citation.put("docId", doc.get("docId"));
            citation.put("topic", doc.get("topic"));
            citation.put("relevanceScore", doc.get("rerankScore"));
            citations.add(citation);
        }

        // Compute confidence based on retrieval quality
        double keywordCoverage = ((Number) rerank.getOrDefault("keywordCoverage", 0.0)).doubleValue();
        int fusedResults = ((Number) retrieval.getOrDefault("fusedResults", 0)).intValue();
        double responseConfidence = Math.min(1.0,
                keywordCoverage * 0.5 + (fusedResults > 0 ? 0.3 : 0.0) + (contextWindow.size() >= 2 ? 0.2 : 0.1));

        long endNanos = System.nanoTime();
        double totalMs = (endNanos - startNanos) / 1_000_000.0;

        // Assemble the complete inference response
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query", query);
        response.put("response", generatedResponse);
        response.put("confidence", Math.round(responseConfidence * 1000.0) / 1000.0);
        response.put("citations", citations);
        response.put("generationParams", Map.of(
                "maxTokens", maxTokens,
                "temperature", temperature,
                "model", "kubefn-llm-7b-simulated"
        ));
        response.put("retrievalStats", Map.of(
                "totalCandidates", retrieval.getOrDefault("totalCandidates", 0),
                "denseMatches", retrieval.getOrDefault("denseMatches", 0),
                "sparseMatches", retrieval.getOrDefault("sparseMatches", 0),
                "fusedResults", fusedResults,
                "contextDocsUsed", contextWindow.size()
        ));
        response.put("_meta", Map.of(
                "pipelineSteps", 6,
                "totalTimeMs", String.format("%.3f", totalMs),
                "totalTimeNanos", endNanos - startNanos,
                "stepTimings", Map.of(
                        "hydrateMs", formatMs(afterHydrate - startNanos),
                        "featuresMs", formatMs(afterFeatures - afterHydrate),
                        "embeddingMs", formatMs(afterEmbed - afterFeatures),
                        "retrievalMs", formatMs(afterRetrieval - afterEmbed),
                        "rerankMs", formatMs(afterRerank - afterRetrieval),
                        "generationMs", formatMs(afterGeneration - afterRerank)
                ),
                "heapObjectsUsed", ctx.heap().keys().size(),
                "zeroCopy", true,
                "note", "6-step RAG pipeline composed in-memory. No HTTP calls. No serialization."
        ));

        return KubeFnResponse.ok(response);
    }

    private String simulateGeneration(String query, String context, String systemPrompt, String intent) {
        if (context.isEmpty()) {
            return "I don't have enough context to answer your question about: " + query
                    + ". Please try rephrasing or providing more details.";
        }

        // Build a contextual response based on intent and available context
        StringBuilder response = new StringBuilder();
        switch (intent) {
            case "instructional":
                response.append("Here's how to approach this:\n\n");
                response.append("Based on the available documentation, ");
                break;
            case "troubleshooting":
                response.append("To resolve this issue:\n\n");
                response.append("The documentation suggests ");
                break;
            case "code_generation":
                response.append("Here's an example implementation:\n\n");
                response.append("Based on best practices, ");
                break;
            case "comparative":
                response.append("Here's a comparison:\n\n");
                response.append("According to the documentation, ");
                break;
            case "definitional":
                response.append("Here's an explanation:\n\n");
                break;
            default:
                response.append("Based on the available knowledge base:\n\n");
        }

        // Extract key sentences from context as the "generated" response
        String[] sentences = context.split("\\.");
        int maxSentences = Math.min(5, sentences.length);
        for (int i = 0; i < maxSentences; i++) {
            String sentence = sentences[i].trim();
            if (!sentence.isEmpty() && !sentence.startsWith("[")) {
                response.append(sentence).append(". ");
            }
        }

        response.append("\n\nFor more details, refer to the cited sources.");
        return response.toString();
    }

    private String formatMs(long nanos) {
        return String.format("%.3f", nanos / 1_000_000.0);
    }
}

package com.enterprise.search;

import io.kubefn.api.*;

import java.util.*;

/**
 * Orchestrates the full search pipeline: parse -> index lookup -> personalize -> rank.
 * All four stages execute in-memory via HeapExchange with zero serialization overhead.
 * Reports detailed per-stage timing.
 */
@FnRoute(path = "/search/query", methods = {"GET", "POST"})
@FnGroup("search-engine")
public class SearchOrchestratorFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        long pipelineStart = System.nanoTime();
        List<Map<String, Object>> stageTiming = new ArrayList<>();

        // Stage 1: Query Parsing
        long stageStart = System.nanoTime();
        var parseResponse = ctx.getFunction(QueryParserFunction.class).handle(request);
        long parseNanos = System.nanoTime() - stageStart;
        stageTiming.add(stageTimingEntry("query_parse", parseNanos, parseResponse.statusCode()));

        if (parseResponse.statusCode() != 200) {
            return parseResponse;
        }

        // Stage 2: Index Lookup
        stageStart = System.nanoTime();
        var lookupResponse = ctx.getFunction(IndexLookupFunction.class).handle(request);
        long lookupNanos = System.nanoTime() - stageStart;
        stageTiming.add(stageTimingEntry("index_lookup", lookupNanos, lookupResponse.statusCode()));

        // Stage 3: Personalization
        stageStart = System.nanoTime();
        var personalizeResponse = ctx.getFunction(PersonalizationFunction.class).handle(request);
        long personalizeNanos = System.nanoTime() - stageStart;
        stageTiming.add(stageTimingEntry("personalization", personalizeNanos, personalizeResponse.statusCode()));

        // Stage 4: Ranking
        stageStart = System.nanoTime();
        var rankResponse = ctx.getFunction(RankingFunction.class).handle(request);
        long rankNanos = System.nanoTime() - stageStart;
        stageTiming.add(stageTimingEntry("ranking", rankNanos, rankResponse.statusCode()));

        // Assemble final response from heap
        var parsedQuery = ctx.heap().get("search:parsed-query", Map.class).orElse(Map.of());
        var indexResults = ctx.heap().get("search:index-results", Map.class).orElse(Map.of());
        var ranked = ctx.heap().get("search:ranked-results", Map.class).orElse(Map.of());

        long totalNanos = System.nanoTime() - pipelineStart;
        double totalMs = totalNanos / 1_000_000.0;

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query", parsedQuery.get("originalQuery"));
        response.put("intent", parsedQuery.get("intent"));
        response.put("results", ranked.get("rankedResults"));
        response.put("totalHits", indexResults.get("totalHits"));
        response.put("diversityApplied", ranked.get("diversityApplied"));
        response.put("_meta", Map.of(
                "pipelineStages", 4,
                "stageTiming", stageTiming,
                "totalTimeMs", String.format("%.3f", totalMs),
                "totalTimeNanos", totalNanos,
                "heapKeysUsed", ctx.heap().keys().size(),
                "zeroCopy", true,
                "note", "Full search pipeline executed in-memory. Zero HTTP calls between stages."
        ));

        return KubeFnResponse.ok(response);
    }

    private Map<String, Object> stageTimingEntry(String stage, long nanos, int status) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("stage", stage);
        entry.put("durationNanos", nanos);
        entry.put("durationMs", String.format("%.3f", nanos / 1_000_000.0));
        entry.put("status", status);
        return entry;
    }
}

package com.enterprise.search;

import com.kubefn.api.*;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Parses and normalizes raw search queries into structured search tokens.
 * Handles stop-word removal, synonym expansion, and operator extraction.
 */
@FnRoute(path = "/search/parse", methods = {"POST"})
@FnGroup("search-engine")
public class QueryParserFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "a", "an", "is", "are", "was", "were", "be", "been",
            "and", "or", "but", "in", "on", "at", "to", "for", "of", "with"
    );

    private static final Map<String, List<String>> SYNONYMS = Map.of(
            "laptop", List.of("notebook", "portable computer"),
            "phone", List.of("smartphone", "mobile", "cell phone"),
            "tv", List.of("television", "display", "monitor"),
            "cheap", List.of("affordable", "budget", "inexpensive"),
            "fast", List.of("quick", "high-speed", "rapid")
    );

    private static final Pattern PRICE_RANGE = Pattern.compile(
            "\\$?(\\d+)\\s*-\\s*\\$?(\\d+)");
    private static final Pattern QUOTED_PHRASE = Pattern.compile(
            "\"([^\"]+)\"");

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        String rawQuery = request.queryParam("q")
                .orElse(request.bodyAsString().isBlank() ? "" : request.bodyAsString());

        if (rawQuery.isBlank()) {
            return KubeFnResponse.badRequest(Map.of("error", "Query parameter 'q' is required"));
        }

        // Extract quoted phrases before tokenizing
        List<String> exactPhrases = new ArrayList<>();
        var phraseMatcher = QUOTED_PHRASE.matcher(rawQuery);
        while (phraseMatcher.find()) {
            exactPhrases.add(phraseMatcher.group(1).toLowerCase());
        }
        String withoutQuotes = QUOTED_PHRASE.matcher(rawQuery).replaceAll("");

        // Extract price range if present
        Map<String, Object> priceFilter = new LinkedHashMap<>();
        var priceMatcher = PRICE_RANGE.matcher(withoutQuotes);
        if (priceMatcher.find()) {
            priceFilter.put("min", Double.parseDouble(priceMatcher.group(1)));
            priceFilter.put("max", Double.parseDouble(priceMatcher.group(2)));
        }
        String withoutPrice = PRICE_RANGE.matcher(withoutQuotes).replaceAll("");

        // Tokenize and clean
        String[] rawTokens = withoutPrice.toLowerCase().trim().split("\\s+");
        List<String> tokens = new ArrayList<>();
        List<String> removedStopWords = new ArrayList<>();
        for (String token : rawTokens) {
            String cleaned = token.replaceAll("[^a-z0-9]", "");
            if (cleaned.isEmpty()) continue;
            if (STOP_WORDS.contains(cleaned)) {
                removedStopWords.add(cleaned);
            } else {
                tokens.add(cleaned);
            }
        }

        // Expand synonyms
        Set<String> expandedTerms = new LinkedHashSet<>(tokens);
        for (String token : tokens) {
            List<String> syns = SYNONYMS.get(token);
            if (syns != null) {
                expandedTerms.addAll(syns);
            }
        }

        // Detect intent
        String intent = "general_search";
        if (!priceFilter.isEmpty()) intent = "price_filtered_search";
        if (!exactPhrases.isEmpty()) intent = "exact_match_search";
        if (tokens.size() == 1) intent = "single_term_search";

        Map<String, Object> parsed = new LinkedHashMap<>();
        parsed.put("originalQuery", rawQuery);
        parsed.put("tokens", tokens);
        parsed.put("expandedTerms", new ArrayList<>(expandedTerms));
        parsed.put("exactPhrases", exactPhrases);
        parsed.put("priceFilter", priceFilter.isEmpty() ? null : priceFilter);
        parsed.put("removedStopWords", removedStopWords);
        parsed.put("intent", intent);
        parsed.put("tokenCount", tokens.size());
        parsed.put("expansionCount", expandedTerms.size());

        ctx.heap().publish("search:parsed-query", parsed, Map.class);
        return KubeFnResponse.ok(parsed);
    }
}

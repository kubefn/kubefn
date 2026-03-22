package com.enterprise;

import com.kubefn.api.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Simulates semantic search and RAG retrieval against a knowledge base.
 * Uses the embedding vector for dense retrieval and keywords for sparse
 * retrieval, then fuses results via reciprocal rank fusion (RRF).
 */
@FnRoute(path = "/ai/retrieve", methods = {"POST"})
@FnGroup("ai-pipeline")
public class RetrievalFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    // Simulated knowledge base documents
    private static final List<Map<String, Object>> KNOWLEDGE_BASE = List.of(
            doc("doc-001", "auto-scaling", "Configure Horizontal Pod Autoscaler (HPA) to automatically scale your workloads based on CPU, memory, or custom metrics. Set minReplicas, maxReplicas, and targetCPUUtilizationPercentage in your HPA spec.", "infrastructure"),
            doc("doc-002", "caching-patterns", "Implement cache-aside pattern: check cache first, on miss load from DB and populate cache. Use TTL-based expiration to prevent stale data. Consider write-through for strong consistency requirements.", "architecture"),
            doc("doc-003", "deployment-strategies", "Blue-green deployments maintain two identical environments. Canary deployments gradually shift traffic. Rolling updates replace pods incrementally with zero downtime.", "devops"),
            doc("doc-004", "monitoring-setup", "Set up Prometheus for metrics collection, Grafana for visualization, and Alertmanager for alerting. Use ServiceMonitor CRDs for automatic scrape target discovery.", "observability"),
            doc("doc-005", "security-best-practices", "Enable RBAC for fine-grained access control. Use network policies to restrict pod-to-pod communication. Rotate secrets regularly and use external secret management.", "security"),
            doc("doc-006", "database-migration", "Use Flyway or Liquibase for schema versioning. Always test migrations on staging first. Implement backward-compatible changes to support rolling deployments.", "data"),
            doc("doc-007", "service-mesh", "Istio provides traffic management, security, and observability for microservices. Use VirtualService for traffic routing and DestinationRule for load balancing policies.", "networking"),
            doc("doc-008", "error-handling", "Implement circuit breakers with Resilience4j to prevent cascade failures. Use exponential backoff for retries. Set appropriate timeouts for all external calls.", "reliability"),
            doc("doc-009", "performance-tuning", "Profile JVM applications with async-profiler. Tune GC with G1GC for latency-sensitive workloads. Set appropriate resource requests and limits in pod specs.", "performance"),
            doc("doc-010", "ci-cd-pipeline", "Automate builds with GitHub Actions or Jenkins. Run unit tests, integration tests, and security scans in pipeline. Use GitOps with ArgoCD for deployment automation.", "devops"),
            doc("doc-011", "kubernetes-networking", "Services provide stable endpoints for pods. Ingress controllers handle external traffic routing. Use headless services for StatefulSet DNS resolution.", "networking"),
            doc("doc-012", "logging-best-practices", "Use structured JSON logging for machine parseability. Centralize logs with EFK stack (Elasticsearch, Fluentd, Kibana). Include correlation IDs across service boundaries.", "observability")
    );

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var features = ctx.heap().get("ai:features", Map.class).orElse(Map.of());
        var embedding = ctx.heap().get("ai:embedding", Map.class).orElse(Map.of());
        var aiContext = ctx.heap().get("ai:context", Map.class).orElse(Map.of());

        List<String> keywords = (List<String>) features.getOrDefault("keywords", List.of());
        List<String> expandedTerms = (List<String>) features.getOrDefault("expandedTerms", List.of());
        int topK = ((Number) features.getOrDefault("retrievalTopK", 5)).intValue();
        double threshold = ((Number) features.getOrDefault("similarityThreshold", 0.7)).doubleValue();
        List<String> scopes = (List<String>) aiContext.getOrDefault("permissionScopes", List.of("public"));

        // Dense retrieval: simulate cosine similarity against knowledge base
        List<Map<String, Object>> denseResults = new ArrayList<>();
        for (var doc : KNOWLEDGE_BASE) {
            double similarity = computeSimilarity(
                    (String) features.getOrDefault("originalQuery", ""),
                    (String) doc.get("content"),
                    (String) doc.get("topic"));
            if (similarity >= threshold * 0.8) { // slightly lower threshold for dense
                Map<String, Object> result = new LinkedHashMap<>(doc);
                result.put("denseScore", Math.round(similarity * 10000.0) / 10000.0);
                denseResults.add(result);
            }
        }

        // Sparse retrieval: keyword matching with BM25-style scoring
        List<Map<String, Object>> sparseResults = new ArrayList<>();
        for (var doc : KNOWLEDGE_BASE) {
            double bm25Score = computeBM25((String) doc.get("content"), keywords, expandedTerms);
            if (bm25Score > 0.1) {
                Map<String, Object> result = new LinkedHashMap<>(doc);
                result.put("sparseScore", Math.round(bm25Score * 10000.0) / 10000.0);
                sparseResults.add(result);
            }
        }

        // Reciprocal Rank Fusion
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        int k = 60; // RRF constant
        denseResults.sort((a, b) -> Double.compare(
                ((Number) b.get("denseScore")).doubleValue(),
                ((Number) a.get("denseScore")).doubleValue()));
        for (int i = 0; i < denseResults.size(); i++) {
            String docId = (String) denseResults.get(i).get("docId");
            rrfScores.merge(docId, 0.7 / (k + i + 1), Double::sum);
        }
        sparseResults.sort((a, b) -> Double.compare(
                ((Number) b.get("sparseScore")).doubleValue(),
                ((Number) a.get("sparseScore")).doubleValue()));
        for (int i = 0; i < sparseResults.size(); i++) {
            String docId = (String) sparseResults.get(i).get("docId");
            rrfScores.merge(docId, 0.3 / (k + i + 1), Double::sum);
        }

        // Build final ranked results
        List<Map<String, Object>> finalResults = new ArrayList<>();
        rrfScores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(topK)
                .forEach(entry -> {
                    String docId = entry.getKey();
                    KNOWLEDGE_BASE.stream()
                            .filter(d -> docId.equals(d.get("docId")))
                            .findFirst()
                            .ifPresent(doc -> {
                                Map<String, Object> result = new LinkedHashMap<>(doc);
                                result.put("rrfScore", Math.round(entry.getValue() * 100000.0) / 100000.0);
                                finalResults.add(result);
                            });
                });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("retrievedDocuments", finalResults);
        result.put("totalCandidates", KNOWLEDGE_BASE.size());
        result.put("denseMatches", denseResults.size());
        result.put("sparseMatches", sparseResults.size());
        result.put("fusedResults", finalResults.size());
        result.put("retrievalMethod", "hybrid_rrf");
        result.put("topK", topK);
        result.put("similarityThreshold", threshold);

        ctx.heap().publish("ai:retrieval", result, Map.class);

        return KubeFnResponse.ok(result);
    }

    private double computeSimilarity(String query, String docContent, String topic) {
        // Simulated semantic similarity based on word overlap and topic relevance
        String[] queryWords = query.toLowerCase().split("\\s+");
        String docLower = docContent.toLowerCase();
        int matches = 0;
        for (String word : queryWords) {
            if (word.length() > 2 && docLower.contains(word)) matches++;
        }
        double overlap = queryWords.length > 0 ? (double) matches / queryWords.length : 0;
        // Boost if topic matches query words
        for (String word : queryWords) {
            if (topic.toLowerCase().contains(word)) {
                overlap = Math.min(overlap + 0.2, 1.0);
                break;
            }
        }
        return Math.min(overlap * 1.5, 1.0); // Scale up for more realistic scores
    }

    private double computeBM25(String docContent, List<String> keywords, List<String> expanded) {
        String docLower = docContent.toLowerCase();
        double score = 0.0;
        for (String kw : keywords) {
            if (docLower.contains(kw.toLowerCase())) {
                score += 1.5; // Primary keyword match
            }
        }
        for (String term : expanded) {
            if (!keywords.contains(term) && docLower.contains(term.toLowerCase())) {
                score += 0.5; // Expanded term match
            }
        }
        // Normalize by doc length
        int docWords = docContent.split("\\s+").length;
        return score / (1.0 + docWords / 50.0);
    }

    private static Map<String, Object> doc(String id, String topic, String content, String category) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("docId", id);
        d.put("topic", topic);
        d.put("content", content);
        d.put("category", category);
        return d;
    }
}

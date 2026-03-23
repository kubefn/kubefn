package com.kubefn.runtime.heap;

import com.kubefn.api.Consumes;
import com.kubefn.api.KubeFnHandler;
import com.kubefn.api.Produces;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Heap diagnostics — generates helpful error messages when heap.get() misses.
 *
 * <p>Instead of just "Optional.empty()", developers get:
 * <pre>
 * HeapExchange: key 'pricing:current' not found.
 *   Expected type: PricingResult
 *   Produced by: checkout-pipeline.PricingFunction (@Produces)
 *   Hint: Ensure PricingFunction runs before TaxFunction.
 * </pre>
 *
 * <p>Built from @Produces/@Consumes annotations discovered during function loading.
 */
public class HeapDiagnostics {

    private static final Logger log = LoggerFactory.getLogger(HeapDiagnostics.class);

    // key pattern → producer info
    private final ConcurrentHashMap<String, ProducerInfo> knownProducers = new ConcurrentHashMap<>();
    // key pattern → consumer info list
    private final ConcurrentHashMap<String, List<String>> knownConsumers = new ConcurrentHashMap<>();

    /**
     * Register a function's @Produces and @Consumes annotations.
     * Called during function loading.
     */
    public void registerFunction(String group, String functionName, Class<?> handlerClass) {
        Produces produces = handlerClass.getAnnotation(Produces.class);
        if (produces != null) {
            for (String key : produces.value()) {
                knownProducers.put(key, new ProducerInfo(group, functionName, handlerClass.getSimpleName()));
                log.debug("Registered producer: {} → {}.{}", key, group, functionName);
            }
        }

        Consumes consumes = handlerClass.getAnnotation(Consumes.class);
        if (consumes != null) {
            for (String key : consumes.value()) {
                knownConsumers.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(group + "." + functionName);
            }
        }
    }

    /**
     * Generate a diagnostic message for a heap miss.
     *
     * @param key the key that was not found
     * @param expectedType the type the consumer expected
     * @param consumerGroup the group of the function that tried to read
     * @param consumerFunction the function that tried to read
     * @return a helpful diagnostic message, or null if no info available
     */
    public String diagnoseMiss(String key, String expectedType,
                               String consumerGroup, String consumerFunction) {
        StringBuilder msg = new StringBuilder();
        msg.append("HeapExchange: key '").append(key).append("' not found.");

        if (expectedType != null) {
            msg.append("\n  Expected type: ").append(expectedType);
        }

        msg.append("\n  Consumer: ").append(consumerGroup).append(".").append(consumerFunction);

        // Check if we know the producer
        ProducerInfo producer = findProducer(key);
        if (producer != null) {
            msg.append("\n  Produced by: ").append(producer.group).append(".")
                    .append(producer.functionName).append(" (@Produces)");
            msg.append("\n  Hint: Ensure ").append(producer.functionName)
                    .append(" runs before ").append(consumerFunction).append(".");
        } else {
            msg.append("\n  No known producer for this key.");
            msg.append("\n  Hint: Check HeapKeys constants, or add @Produces to the producing function.");
        }

        return msg.toString();
    }

    /**
     * Generate the function dependency graph from @Produces/@Consumes.
     */
    public Map<String, Object> dependencyGraph() {
        var graph = new java.util.LinkedHashMap<String, Object>();

        var producers = new java.util.LinkedHashMap<String, String>();
        knownProducers.forEach((key, info) ->
                producers.put(key, info.group + "." + info.functionName));

        var consumers = new java.util.LinkedHashMap<String, List<String>>();
        knownConsumers.forEach((key, fns) -> consumers.put(key, new ArrayList<>(fns)));

        graph.put("producers", producers);
        graph.put("consumers", consumers);
        graph.put("totalProducers", knownProducers.size());
        graph.put("totalConsumers", knownConsumers.size());

        return graph;
    }

    private ProducerInfo findProducer(String key) {
        // Exact match
        ProducerInfo exact = knownProducers.get(key);
        if (exact != null) return exact;

        // Pattern match (e.g., "auth:*" matches "auth:user-001")
        for (Map.Entry<String, ProducerInfo> entry : knownProducers.entrySet()) {
            String pattern = entry.getKey();
            if (pattern.endsWith("*")) {
                String prefix = pattern.substring(0, pattern.length() - 1);
                if (key.startsWith(prefix)) {
                    return entry.getValue();
                }
            }
        }

        return null;
    }

    record ProducerInfo(String group, String functionName, String className) {}
}

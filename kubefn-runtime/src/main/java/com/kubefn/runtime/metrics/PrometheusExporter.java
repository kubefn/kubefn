package com.kubefn.runtime.metrics;

import com.kubefn.runtime.heap.HeapExchangeImpl;
import com.kubefn.runtime.resilience.FunctionCircuitBreaker;
import com.kubefn.runtime.routing.FunctionRouter;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.Map;

/**
 * Prometheus metrics exporter. Generates Prometheus text format
 * from KubeFn runtime metrics.
 *
 * <p>Exposes at /admin/prometheus for scraping.
 *
 * <h3>Metrics exported:</h3>
 * <ul>
 *   <li>kubefn_requests_total — total requests (counter)</li>
 *   <li>kubefn_request_duration_seconds — per-function latency histogram</li>
 *   <li>kubefn_errors_total — total errors (counter)</li>
 *   <li>kubefn_heap_objects — current HeapExchange object count (gauge)</li>
 *   <li>kubefn_heap_publishes_total — total heap publishes (counter)</li>
 *   <li>kubefn_heap_hit_rate — heap hit rate (gauge)</li>
 *   <li>kubefn_circuit_breaker_state — per-function breaker state (gauge)</li>
 *   <li>kubefn_jvm_heap_bytes — JVM heap usage (gauge)</li>
 *   <li>kubefn_jvm_gc_seconds_total — GC time (counter)</li>
 *   <li>kubefn_routes_total — total registered routes (gauge)</li>
 * </ul>
 */
public class PrometheusExporter {

    private final KubeFnMetrics metrics;
    private final HeapExchangeImpl heap;
    private final FunctionCircuitBreaker circuitBreaker;
    private final FunctionRouter router;

    public PrometheusExporter(KubeFnMetrics metrics, HeapExchangeImpl heap,
                              FunctionCircuitBreaker circuitBreaker, FunctionRouter router) {
        this.metrics = metrics;
        this.heap = heap;
        this.circuitBreaker = circuitBreaker;
        this.router = router;
    }

    /**
     * Generate Prometheus text format metrics.
     */
    public String export() {
        StringBuilder sb = new StringBuilder();
        Map<String, Object> snapshot = metrics.snapshot();

        // ─── Request metrics ────────────────────────────────────
        appendCounter(sb, "kubefn_requests_total",
                "Total requests processed",
                ((Number) snapshot.get("totalRequests")).longValue());

        appendCounter(sb, "kubefn_errors_total",
                "Total request errors",
                ((Number) snapshot.get("totalErrors")).longValue());

        appendCounter(sb, "kubefn_timeouts_total",
                "Total request timeouts",
                ((Number) snapshot.get("totalTimeouts")).longValue());

        appendCounter(sb, "kubefn_breaker_trips_total",
                "Total circuit breaker trips",
                ((Number) snapshot.get("totalBreakerTrips")).longValue());

        // Per-function metrics
        @SuppressWarnings("unchecked")
        var functions = (java.util.List<Map<String, Object>>) snapshot.get("functions");
        if (functions != null) {
            sb.append("# HELP kubefn_function_requests_total Total requests per function\n");
            sb.append("# TYPE kubefn_function_requests_total counter\n");
            for (Map<String, Object> fn : functions) {
                String name = (String) fn.get("function");
                String[] parts = name.split("\\.", 2);
                String group = parts.length > 1 ? parts[0] : "default";
                String function = parts.length > 1 ? parts[1] : name;

                sb.append(String.format("kubefn_function_requests_total{group=\"%s\",function=\"%s\"} %d\n",
                        group, function, ((Number) fn.get("count")).longValue()));
            }

            sb.append("# HELP kubefn_function_duration_seconds Function execution duration\n");
            sb.append("# TYPE kubefn_function_duration_seconds summary\n");
            for (Map<String, Object> fn : functions) {
                String name = (String) fn.get("function");
                String[] parts = name.split("\\.", 2);
                String group = parts.length > 1 ? parts[0] : "default";
                String function = parts.length > 1 ? parts[1] : name;
                String labels = String.format("group=\"%s\",function=\"%s\"", group, function);

                sb.append(String.format("kubefn_function_duration_seconds{%s,quantile=\"0.5\"} %s\n",
                        labels, msToSeconds((String) fn.get("p50Ms"))));
                sb.append(String.format("kubefn_function_duration_seconds{%s,quantile=\"0.95\"} %s\n",
                        labels, msToSeconds((String) fn.get("p95Ms"))));
                sb.append(String.format("kubefn_function_duration_seconds{%s,quantile=\"0.99\"} %s\n",
                        labels, msToSeconds((String) fn.get("p99Ms"))));
                sb.append(String.format("kubefn_function_duration_seconds_count{%s} %d\n",
                        labels, ((Number) fn.get("count")).longValue()));
            }
        }

        // ─── HeapExchange metrics ───────────────────────────────
        var heapMetrics = heap.metrics();
        appendGauge(sb, "kubefn_heap_objects",
                "Current HeapExchange object count", heapMetrics.objectCount());

        appendCounter(sb, "kubefn_heap_publishes_total",
                "Total HeapExchange publishes", heapMetrics.publishCount());

        appendCounter(sb, "kubefn_heap_gets_total",
                "Total HeapExchange gets", heapMetrics.getCount());

        appendCounter(sb, "kubefn_heap_hits_total",
                "Total HeapExchange hits", heapMetrics.hitCount());

        appendGauge(sb, "kubefn_heap_hit_rate",
                "HeapExchange hit rate", heapMetrics.hitRate());

        // ─── Circuit breakers ───────────────────────────────────
        var breakers = circuitBreaker.allStatus();
        if (!breakers.isEmpty()) {
            sb.append("# HELP kubefn_circuit_breaker_state Circuit breaker state (0=closed, 1=open, 2=half-open)\n");
            sb.append("# TYPE kubefn_circuit_breaker_state gauge\n");
            for (var entry : breakers.entrySet()) {
                String name = entry.getKey();
                var status = entry.getValue();
                int state = switch (status.state()) {
                    case "CLOSED" -> 0;
                    case "OPEN" -> 1;
                    case "HALF_OPEN" -> 2;
                    default -> -1;
                };
                sb.append(String.format("kubefn_circuit_breaker_state{function=\"%s\"} %d\n",
                        name, state));
            }
        }

        // ─── Routes ─────────────────────────────────────────────
        appendGauge(sb, "kubefn_routes_total",
                "Total registered routes", router.routeCount());

        // ─── JVM metrics ────────────────────────────────────────
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        long heapUsed = memory.getHeapMemoryUsage().getUsed();
        long heapMax = memory.getHeapMemoryUsage().getMax();

        appendGauge(sb, "kubefn_jvm_heap_used_bytes",
                "JVM heap used bytes", heapUsed);
        appendGauge(sb, "kubefn_jvm_heap_max_bytes",
                "JVM heap max bytes", heapMax);
        appendGauge(sb, "kubefn_jvm_threads",
                "JVM thread count", ManagementFactory.getThreadMXBean().getThreadCount());

        // GC metrics
        sb.append("# HELP kubefn_jvm_gc_seconds_total Total GC time in seconds\n");
        sb.append("# TYPE kubefn_jvm_gc_seconds_total counter\n");
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            sb.append(String.format("kubefn_jvm_gc_seconds_total{gc=\"%s\"} %.3f\n",
                    gc.getName(), gc.getCollectionTime() / 1000.0));
            sb.append(String.format("kubefn_jvm_gc_collections_total{gc=\"%s\"} %d\n",
                    gc.getName(), gc.getCollectionCount()));
        }

        return sb.toString();
    }

    private void appendCounter(StringBuilder sb, String name, String help, long value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" counter\n");
        sb.append(name).append(' ').append(value).append('\n');
    }

    private void appendGauge(StringBuilder sb, String name, String help, long value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" gauge\n");
        sb.append(name).append(' ').append(value).append('\n');
    }

    private void appendGauge(StringBuilder sb, String name, String help, double value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" gauge\n");
        sb.append(name).append(' ').append(String.format("%.4f", value)).append('\n');
    }

    private String msToSeconds(String ms) {
        try {
            return String.format("%.6f", Double.parseDouble(ms) / 1000.0);
        } catch (NumberFormatException e) {
            return "0.000000";
        }
    }
}

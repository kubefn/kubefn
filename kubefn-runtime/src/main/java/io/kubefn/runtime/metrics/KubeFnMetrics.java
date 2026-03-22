package io.kubefn.runtime.metrics;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Centralized metrics for the KubeFn runtime.
 * Provides per-function latency histograms, request counters,
 * heap stats, and circuit breaker metrics.
 *
 * <p>Uses Micrometer for portability — can export to Prometheus,
 * Datadog, CloudWatch, etc.
 */
public class KubeFnMetrics {

    private static final KubeFnMetrics INSTANCE = new KubeFnMetrics();

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Timer> functionTimers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> functionErrors = new ConcurrentHashMap<>();

    // Global counters
    private final Counter totalRequests;
    private final Counter totalErrors;
    private final Counter totalTimeouts;
    private final Counter totalCircuitBreakerTrips;
    private final Counter heapPublishes;
    private final Counter heapGets;
    private final Counter heapHits;
    private final Counter heapMisses;

    private KubeFnMetrics() {
        this.registry = new SimpleMeterRegistry();

        this.totalRequests = Counter.builder("kubefn.requests.total")
                .description("Total requests processed")
                .register(registry);
        this.totalErrors = Counter.builder("kubefn.errors.total")
                .description("Total request errors")
                .register(registry);
        this.totalTimeouts = Counter.builder("kubefn.timeouts.total")
                .description("Total request timeouts")
                .register(registry);
        this.totalCircuitBreakerTrips = Counter.builder("kubefn.breaker.trips.total")
                .description("Total circuit breaker trips")
                .register(registry);
        this.heapPublishes = Counter.builder("kubefn.heap.publishes.total")
                .description("Total HeapExchange publishes")
                .register(registry);
        this.heapGets = Counter.builder("kubefn.heap.gets.total")
                .description("Total HeapExchange gets")
                .register(registry);
        this.heapHits = Counter.builder("kubefn.heap.hits.total")
                .description("Total HeapExchange cache hits")
                .register(registry);
        this.heapMisses = Counter.builder("kubefn.heap.misses.total")
                .description("Total HeapExchange cache misses")
                .register(registry);
    }

    public static KubeFnMetrics instance() { return INSTANCE; }

    /**
     * Record a function invocation with latency.
     */
    public void recordInvocation(String group, String function, long durationNanos, boolean success) {
        String key = group + "." + function;

        Timer timer = functionTimers.computeIfAbsent(key, k ->
                Timer.builder("kubefn.function.duration")
                        .tag("group", group)
                        .tag("function", function)
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .publishPercentileHistogram()
                        .register(registry));
        timer.record(durationNanos, TimeUnit.NANOSECONDS);

        totalRequests.increment();

        if (!success) {
            totalErrors.increment();
            functionErrors.computeIfAbsent(key, k ->
                    Counter.builder("kubefn.function.errors")
                            .tag("group", group)
                            .tag("function", function)
                            .register(registry)).increment();
        }
    }

    public void recordTimeout() { totalTimeouts.increment(); }
    public void recordBreakerTrip() { totalCircuitBreakerTrips.increment(); }
    public void recordHeapPublish() { heapPublishes.increment(); }
    public void recordHeapGet(boolean hit) {
        heapGets.increment();
        if (hit) heapHits.increment(); else heapMisses.increment();
    }

    /**
     * Get a snapshot of all metrics for the admin endpoint.
     */
    public java.util.Map<String, Object> snapshot() {
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("totalRequests", (long) totalRequests.count());
        result.put("totalErrors", (long) totalErrors.count());
        result.put("totalTimeouts", (long) totalTimeouts.count());
        result.put("totalBreakerTrips", (long) totalCircuitBreakerTrips.count());
        result.put("heapPublishes", (long) heapPublishes.count());
        result.put("heapGets", (long) heapGets.count());
        result.put("heapHits", (long) heapHits.count());
        result.put("heapMisses", (long) heapMisses.count());

        // Per-function stats
        var functionStats = new java.util.ArrayList<java.util.Map<String, Object>>();
        functionTimers.forEach((key, timer) -> {
            var stats = new java.util.LinkedHashMap<String, Object>();
            stats.put("function", key);
            stats.put("count", timer.count());
            stats.put("totalTimeMs", String.format("%.3f", timer.totalTime(TimeUnit.MILLISECONDS)));
            stats.put("meanMs", String.format("%.3f", timer.mean(TimeUnit.MILLISECONDS)));
            stats.put("maxMs", String.format("%.3f", timer.max(TimeUnit.MILLISECONDS)));
            stats.put("p50Ms", String.format("%.3f", timer.percentile(0.5, TimeUnit.MILLISECONDS)));
            stats.put("p95Ms", String.format("%.3f", timer.percentile(0.95, TimeUnit.MILLISECONDS)));
            stats.put("p99Ms", String.format("%.3f", timer.percentile(0.99, TimeUnit.MILLISECONDS)));
            functionStats.add(stats);
        });
        result.put("functions", functionStats);

        return result;
    }

    public MeterRegistry registry() { return registry; }
}

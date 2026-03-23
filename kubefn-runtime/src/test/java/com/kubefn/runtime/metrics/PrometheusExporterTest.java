package com.kubefn.runtime.metrics;

import com.kubefn.runtime.heap.HeapExchangeImpl;
import com.kubefn.runtime.resilience.FunctionCircuitBreaker;
import com.kubefn.runtime.routing.FunctionRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PrometheusExporterTest {

    private PrometheusExporter exporter;
    private HeapExchangeImpl heap;

    @BeforeEach
    void setUp() {
        heap = new HeapExchangeImpl();
        var breaker = new FunctionCircuitBreaker();
        var router = new FunctionRouter();
        exporter = new PrometheusExporter(KubeFnMetrics.instance(), heap, breaker, router);
    }

    @Test
    void exportProducesValidPrometheusFormat() {
        String output = exporter.export();

        // Should contain required sections
        assertTrue(output.contains("# HELP kubefn_requests_total"));
        assertTrue(output.contains("# TYPE kubefn_requests_total counter"));
        assertTrue(output.contains("kubefn_requests_total"));

        // Should have heap metrics
        assertTrue(output.contains("kubefn_heap_objects"));
        assertTrue(output.contains("kubefn_heap_publishes_total"));

        // Should have JVM metrics
        assertTrue(output.contains("kubefn_jvm_heap_used_bytes"));
        assertTrue(output.contains("kubefn_jvm_threads"));
        assertTrue(output.contains("kubefn_jvm_gc_seconds_total"));

        // Should have routes
        assertTrue(output.contains("kubefn_routes_total"));
    }

    @Test
    void heapMetricsReflectState() {
        HeapExchangeImpl.setCurrentContext("test", "test");
        heap.publish("k1", "v1", String.class);
        heap.publish("k2", "v2", String.class);

        String output = exporter.export();
        assertTrue(output.contains("kubefn_heap_objects 2"));
    }

    @Test
    void noNullPointers() {
        // Should not throw even with empty state
        String output = exporter.export();
        assertNotNull(output);
        assertFalse(output.isEmpty());
    }
}

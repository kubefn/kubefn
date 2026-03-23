package com.kubefn.runtime.heap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

class HeapLifecycleTest {

    private HeapExchangeImpl heap;
    private HeapLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        heap = new HeapExchangeImpl();
        HeapExchangeImpl.setCurrentContext("test", "test");
        lifecycle = new HeapLifecycle(heap, heap.guard());
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutdown();
    }

    @Test
    void ttlEvictsExpiredKeys() throws InterruptedException {
        heap.publish("k1", "value1", String.class);
        lifecycle.setTTL("k1", 200); // 200ms TTL

        assertTrue(heap.contains("k1"));
        Thread.sleep(300); // Wait for TTL + eviction cycle
        lifecycle.evictionCycle(); // Force cycle

        assertFalse(heap.contains("k1"), "Key should be evicted after TTL");
    }

    @Test
    void permanentKeysNotEvicted() throws InterruptedException {
        heap.publish("permanent", "value", String.class);
        // No TTL set — permanent

        Thread.sleep(100);
        lifecycle.evictionCycle();

        assertTrue(heap.contains("permanent"), "Permanent key should survive");
    }

    @Test
    void requestScopedKeysEvictedOnComplete() {
        String requestId = "req-123";
        heap.publish("req:" + requestId + ":temp1", "v1", String.class);
        heap.publish("req:" + requestId + ":temp2", "v2", String.class);
        heap.publish("other-key", "v3", String.class);

        assertEquals(3, heap.keys().size());

        lifecycle.onRequestComplete(requestId);

        assertEquals(1, heap.keys().size());
        assertFalse(heap.contains("req:" + requestId + ":temp1"));
        assertTrue(heap.contains("other-key"));
    }

    @Test
    void patternBasedTTL() throws InterruptedException {
        lifecycle.configureTTL("pricing:*", 200);

        heap.publish("pricing:current", "value", String.class);
        lifecycle.onPublish("pricing:current");

        heap.publish("other:key", "value", String.class);
        lifecycle.onPublish("other:key");

        Thread.sleep(300);
        lifecycle.evictionCycle();

        assertFalse(heap.contains("pricing:current"), "Pattern-matched key should be evicted");
        assertTrue(heap.contains("other:key"), "Non-matching key should survive");
    }

    @Test
    void metricsReportCorrectly() {
        heap.publish("k1", "v1", String.class);
        heap.publish("k2", "v2", String.class);

        var metrics = lifecycle.metrics();
        assertEquals(2, metrics.get("heapObjects"));
        assertTrue(((long) metrics.get("jvmHeapUsedMB")) > 0);
    }
}

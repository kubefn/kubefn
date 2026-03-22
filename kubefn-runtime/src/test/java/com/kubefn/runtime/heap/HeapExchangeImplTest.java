package com.kubefn.runtime.heap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HeapExchangeImplTest {

    private HeapExchangeImpl exchange;

    @BeforeEach
    void setUp() {
        exchange = new HeapExchangeImpl();
        HeapExchangeImpl.setCurrentContext("test-group", "TestFunction");
    }

    @Test
    void publishAndGetReturnsSameObject() {
        Map<String, String> data = Map.of("hello", "world");
        exchange.publish("key1", data, Map.class);

        var result = exchange.get("key1", Map.class);
        assertTrue(result.isPresent());
        // ZERO COPY: must be the exact same object reference
        assertSame(data, result.get());
    }

    @Test
    void getReturnsEmptyForMissingKey() {
        var result = exchange.get("nonexistent", String.class);
        assertTrue(result.isEmpty());
    }

    @Test
    void getReturnsEmptyForTypeMismatch() {
        exchange.publish("key1", "hello", String.class);
        // Try to get as Integer — should return empty, not crash
        var result = exchange.get("key1", Integer.class);
        assertTrue(result.isEmpty());
    }

    @Test
    void publishOverwritesPreviousValue() {
        exchange.publish("key1", "v1", String.class);
        exchange.publish("key1", "v2", String.class);

        assertEquals("v2", exchange.get("key1", String.class).orElse(""));
    }

    @Test
    void removeDeletesObject() {
        exchange.publish("key1", "value", String.class);
        assertTrue(exchange.contains("key1"));

        assertTrue(exchange.remove("key1"));
        assertFalse(exchange.contains("key1"));
        assertTrue(exchange.get("key1", String.class).isEmpty());
    }

    @Test
    void removeReturnsFalseForMissingKey() {
        assertFalse(exchange.remove("nonexistent"));
    }

    @Test
    void keysReturnsAllPublishedKeys() {
        exchange.publish("a", "1", String.class);
        exchange.publish("b", "2", String.class);
        exchange.publish("c", "3", String.class);

        var keys = exchange.keys();
        assertEquals(3, keys.size());
        assertTrue(keys.contains("a"));
        assertTrue(keys.contains("b"));
        assertTrue(keys.contains("c"));
    }

    @Test
    void metricsTrackPublishesAndGets() {
        exchange.publish("k1", "v1", String.class);
        exchange.publish("k2", "v2", String.class);
        exchange.get("k1", String.class); // hit
        exchange.get("k2", String.class); // hit
        exchange.get("k3", String.class); // miss

        var metrics = exchange.metrics();
        assertEquals(2, metrics.objectCount());
        assertEquals(2, metrics.publishCount());
        assertEquals(3, metrics.getCount());
        assertEquals(2, metrics.hitCount());
        assertEquals(1, metrics.missCount());
        assertTrue(metrics.hitRate() > 0.6);
    }

    @Test
    void getCapsuleIncludesMetadata() {
        exchange.publish("key1", "hello", String.class);

        var capsule = exchange.getCapsule("key1", String.class);
        assertTrue(capsule.isPresent());
        assertEquals("key1", capsule.get().key());
        assertEquals("test-group", capsule.get().publisherGroup());
        assertEquals("TestFunction", capsule.get().publisherFunction());
        assertEquals(String.class, capsule.get().type());
        assertTrue(capsule.get().version() > 0);
    }

    @Test
    void auditLogRecordsMutations() {
        exchange.publish("k1", "v1", String.class);
        exchange.get("k1", String.class);
        exchange.remove("k1");

        var log = exchange.auditLog();
        var mutations = log.mutationsForKey("k1");
        assertEquals(2, mutations.size()); // publish + remove
        // Audit log stores newest-first, but mutationsForKey streams in insertion order
        assertTrue(mutations.stream().anyMatch(m -> m.action() == HeapAuditLog.AuditAction.PUBLISH));
        assertTrue(mutations.stream().anyMatch(m -> m.action() == HeapAuditLog.AuditAction.REMOVE));
    }

    @Test
    void guardBlocksWhenAtCapacity() {
        // Create exchange with tiny limit
        var guard = new HeapGuard(2, 1024 * 1024, 3600_000);
        var smallExchange = new HeapExchangeImpl(guard, new HeapAuditLog());
        HeapExchangeImpl.setCurrentContext("test", "test");

        smallExchange.publish("k1", "v1", String.class);
        smallExchange.publish("k2", "v2", String.class);

        // Third publish should throw — at capacity
        assertThrows(IllegalStateException.class, () ->
                smallExchange.publish("k3", "v3", String.class));
    }
}

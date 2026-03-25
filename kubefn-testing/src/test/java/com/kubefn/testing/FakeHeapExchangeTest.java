package com.kubefn.testing;

import com.kubefn.api.HeapKey;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FakeHeapExchangeTest {

    // ── Test keys ──────────────────────────────────────────────────

    static final HeapKey<String> GREETING = HeapKey.of("test:greeting", String.class);
    static final HeapKey<Integer> COUNT = HeapKey.of("test:count", Integer.class);
    static final HeapKey<Double> SCORE = HeapKey.of("test:score", Double.class);

    // ── with() + chaining ──────────────────────────────────────────

    @Test
    void withHeapKeyChaining() {
        var heap = FakeHeapExchange.create()
                .with(GREETING, "hello")
                .with(COUNT, 42)
                .with(SCORE, 99.5);

        assertEquals(3, heap.size());
        assertEquals("hello", heap.require(GREETING));
        assertEquals(42, heap.require(COUNT));
        assertEquals(99.5, heap.require(SCORE));
    }

    @Test
    void withStringKeyChaining() {
        var heap = FakeHeapExchange.create()
                .with("pricing:current", "USD-100", String.class)
                .with("tax:calculated", 7.5, Double.class);

        assertEquals(2, heap.size());
        assertEquals("USD-100", heap.get("pricing:current", String.class).orElseThrow());
        assertEquals(7.5, heap.get("tax:calculated", Double.class).orElseThrow());
    }

    @Test
    void withResetsCounters() {
        var heap = FakeHeapExchange.create()
                .with(GREETING, "hello")
                .with(COUNT, 42);

        // Counters should be zero after setup via with()
        assertEquals(0, heap.publishCount());
        assertEquals(0, heap.getCount());
        assertEquals(0, heap.hitCount());
        assertEquals(0, heap.missCount());
    }

    // ── require() ──────────────────────────────────────────────────

    @Test
    void requireReturnsValueWhenPresent() {
        var heap = FakeHeapExchange.create().with(GREETING, "world");

        String result = heap.require(GREETING);
        assertEquals("world", result);
    }

    @Test
    void requireThrowsWithHelpfulMessageWhenMissing() {
        var heap = FakeHeapExchange.create();

        var ex = assertThrows(IllegalStateException.class, () -> heap.require(GREETING));

        assertTrue(ex.getMessage().contains("test:greeting"),
                "Error message should contain the key name");
        assertTrue(ex.getMessage().contains("String"),
                "Error message should contain the type name");
        assertTrue(ex.getMessage().contains("producer"),
                "Error message should hint that the producer may not have run");
    }

    // ── publish() + get() with HeapKey<T> ──────────────────────────

    @Test
    void publishAndGetWithHeapKey() {
        var heap = FakeHeapExchange.create();

        heap.publish(COUNT, 100);

        Optional<Integer> result = heap.get(COUNT);
        assertTrue(result.isPresent());
        assertEquals(100, result.get());
    }

    @Test
    void getReturnsEmptyWhenMissing() {
        var heap = FakeHeapExchange.create();

        Optional<Integer> result = heap.get(COUNT);
        assertTrue(result.isEmpty());
    }

    @Test
    void publishOverwritesPreviousValue() {
        var heap = FakeHeapExchange.create();

        heap.publish(COUNT, 1);
        heap.publish(COUNT, 2);

        assertEquals(2, heap.require(COUNT));
    }

    // ── Tracking counters ──────────────────────────────────────────

    @Test
    void countersTrackOperations() {
        var heap = FakeHeapExchange.create().with(GREETING, "hi");

        // These operations happen after with() so they are counted
        heap.publish(COUNT, 10);
        heap.get(GREETING);       // hit
        heap.get(COUNT);           // hit
        heap.get(SCORE);           // miss

        assertEquals(1, heap.publishCount());
        assertEquals(3, heap.getCount());
        assertEquals(2, heap.hitCount());
        assertEquals(1, heap.missCount());
    }

    @Test
    void publishLogRecordsKeys() {
        var heap = FakeHeapExchange.create();

        heap.publish(GREETING, "a");
        heap.publish(COUNT, 1);

        assertEquals(2, heap.publishLog().size());
        assertEquals("test:greeting", heap.publishLog().get(0));
        assertEquals("test:count", heap.publishLog().get(1));
    }

    // ── contains / remove / keys ───────────────────────────────────

    @Test
    void containsAndRemoveWithHeapKey() {
        var heap = FakeHeapExchange.create().with(GREETING, "hi");

        assertTrue(heap.contains(GREETING));
        assertTrue(heap.remove(GREETING));
        assertFalse(heap.contains(GREETING));
        assertEquals(0, heap.size());
    }

    @Test
    void keysReturnsAllKeys() {
        var heap = FakeHeapExchange.create()
                .with(GREETING, "hello")
                .with(COUNT, 5);

        var keys = heap.keys();
        assertEquals(2, keys.size());
        assertTrue(keys.contains("test:greeting"));
        assertTrue(keys.contains("test:count"));
    }

    // ── clear / resetCounters ──────────────────────────────────────

    @Test
    void clearRemovesEverything() {
        var heap = FakeHeapExchange.create()
                .with(GREETING, "hello")
                .with(COUNT, 42);

        heap.publish(SCORE, 1.0);
        heap.clear();

        assertEquals(0, heap.size());
        assertEquals(0, heap.publishCount());
    }
}

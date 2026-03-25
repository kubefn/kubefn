package com.kubefn.testing;

import com.kubefn.api.HeapCapsule;
import com.kubefn.api.HeapExchange;
import com.kubefn.api.HeapKey;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A test-friendly HeapExchange implementation for unit testing KubeFn functions.
 *
 * <p>Use this to set up test data, execute your function, and verify heap state:
 *
 * <pre>{@code
 * @Test
 * void calculatesTax() {
 *     var heap = FakeHeapExchange.create()
 *         .with(HeapKeys.PRICING_CURRENT, new PricingResult("USD", 100, 0.1, 90))
 *         .with(HeapKeys.auth("user-1"), new AuthContext("user-1", true, "premium", List.of(), List.of(), 0, "s1"));
 *
 *     var ctx = FakeFnContext.of(heap);
 *     var fn = new TaxFunction();
 *     fn.setContext(ctx);
 *     fn.handle(TestRequest.empty());
 *
 *     TaxCalculation tax = heap.require(HeapKeys.TAX_CALCULATED);
 *     assertEquals(7.43, tax.taxAmount(), 0.01);
 * }
 * }</pre>
 */
public final class FakeHeapExchange implements HeapExchange {

    private final ConcurrentHashMap<String, Object> store = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, HeapCapsule<?>> capsules = new ConcurrentHashMap<>();
    private final AtomicLong version = new AtomicLong(0);

    // Tracking for assertions
    private final List<String> publishLog = new ArrayList<>();
    private final List<String> getLog = new ArrayList<>();
    private int publishCount = 0;
    private int getCount = 0;
    private int hitCount = 0;
    private int missCount = 0;

    private FakeHeapExchange() {}

    /** Create a new empty FakeHeapExchange. */
    public static FakeHeapExchange create() {
        return new FakeHeapExchange();
    }

    /**
     * Fluent builder: seed the heap with a typed value using a HeapKey.
     *
     * <pre>{@code
     * var heap = FakeHeapExchange.create()
     *     .with(HeapKeys.PRICING_CURRENT, pricingResult)
     *     .with(HeapKeys.auth("user-1"), authContext);
     * }</pre>
     */
    public <T> FakeHeapExchange with(HeapKey<T> key, T value) {
        publish(key, value);
        // Reset counters since this is setup, not test execution
        resetCounters();
        return this;
    }

    /**
     * Fluent builder: seed the heap with a string-keyed typed value.
     *
     * <pre>{@code
     * var heap = FakeHeapExchange.create()
     *     .with("pricing:current", pricingResult, PricingResult.class);
     * }</pre>
     */
    public <T> FakeHeapExchange with(String key, T value, Class<T> type) {
        publish(key, value, type);
        // Reset counters since this is setup, not test execution
        resetCounters();
        return this;
    }

    // ── String-based API (backward compat) ──────────────────────

    @Override
    @SuppressWarnings("unchecked")
    public <T> HeapCapsule<T> publish(String key, T value, Class<T> type) {
        store.put(key, value);
        long ver = version.incrementAndGet();
        var capsule = new HeapCapsule<>(key, value, type, ver, "test-group", "test-function", java.time.Instant.now());
        capsules.put(key, capsule);
        publishLog.add(key);
        publishCount++;
        return capsule;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(String key, Class<T> type) {
        getCount++;
        Object value = store.get(key);
        if (value != null) {
            hitCount++;
            getLog.add(key + " → HIT");
            return Optional.of(type.cast(value));
        } else {
            missCount++;
            getLog.add(key + " → MISS");
            return Optional.empty();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<HeapCapsule<T>> getCapsule(String key, Class<T> type) {
        return Optional.ofNullable((HeapCapsule<T>) capsules.get(key));
    }

    @Override
    public boolean remove(String key) {
        capsules.remove(key);
        return store.remove(key) != null;
    }

    @Override
    public Set<String> keys() {
        return Collections.unmodifiableSet(store.keySet());
    }

    @Override
    public boolean contains(String key) {
        return store.containsKey(key);
    }

    // ── Assertion helpers ────────────────────────────────────────

    /** Get the number of publish() calls during the test (not setup). */
    public int publishCount() { return publishCount; }

    /** Get the number of get() calls during the test. */
    public int getCount() { return getCount; }

    /** Get the number of cache hits. */
    public int hitCount() { return hitCount; }

    /** Get the number of cache misses. */
    public int missCount() { return missCount; }

    /** Get the ordered list of published keys. */
    public List<String> publishLog() { return Collections.unmodifiableList(publishLog); }

    /** Get the ordered list of get operations with results. */
    public List<String> getLog() { return Collections.unmodifiableList(getLog); }

    /** Get the current size of the heap. */
    public int size() { return store.size(); }

    /** Reset all counters and logs (but keep data). */
    public void resetCounters() {
        publishLog.clear();
        getLog.clear();
        publishCount = 0;
        getCount = 0;
        hitCount = 0;
        missCount = 0;
    }

    /** Clear all data and counters. */
    public void clear() {
        store.clear();
        capsules.clear();
        version.set(0);
        resetCounters();
    }
}

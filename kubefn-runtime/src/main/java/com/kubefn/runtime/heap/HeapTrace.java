package com.kubefn.runtime.heap;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Heap Trace — always-on ring buffer logging every HeapExchange operation.
 *
 * <p>Records every publish, get, and remove with:
 * timestamp, operation, key, producer/consumer class, thread, version.
 *
 * <p>This is the foundation for causal debugging. A developer can run:
 * <pre>
 *   kubefn trace &lt;requestId&gt;   — see all heap ops for a request
 *   kubefn heap log --key X    — see mutation history for a key
 *   kubefn heap diff t1 t2     — what changed between two timestamps
 * </pre>
 *
 * <p>Always-on. Lock-free writes. ~64 bytes per entry.
 * At 10K ops/sec = 640KB/sec = trivial overhead.
 */
public class HeapTrace {

    private final TraceEntry[] ring;
    private final AtomicInteger writePos = new AtomicInteger(0);
    private final AtomicLong totalEntries = new AtomicLong(0);
    private final int capacity;

    public HeapTrace() {
        this(10_000);
    }

    public HeapTrace(int capacity) {
        this.capacity = capacity;
        this.ring = new TraceEntry[capacity];
    }

    /**
     * Record a heap operation. Lock-free — uses atomic CAS on write position.
     */
    public void record(Operation op, String key, String typeName,
                       String group, String function, long version,
                       String requestId, String threadName) {
        int pos = writePos.getAndUpdate(i -> (i + 1) % capacity);
        ring[pos] = new TraceEntry(
                System.nanoTime(),
                Instant.now(),
                op, key, typeName,
                group, function, version,
                requestId, threadName
        );
        totalEntries.incrementAndGet();
    }

    // ── Convenience methods ──

    public void recordPublish(String key, String typeName, String group,
                              String function, long version, String requestId) {
        record(Operation.PUBLISH, key, typeName, group, function, version,
                requestId, Thread.currentThread().getName());
    }

    public void recordGet(String key, String typeName, String group,
                          String function, long version, String requestId, boolean hit) {
        record(hit ? Operation.GET_HIT : Operation.GET_MISS, key, typeName,
                group, function, version, requestId, Thread.currentThread().getName());
    }

    public void recordRemove(String key, String group, String function, String requestId) {
        record(Operation.REMOVE, key, "", group, function, 0,
                requestId, Thread.currentThread().getName());
    }

    // ── Query methods ──

    /**
     * Get all trace entries for a specific request ID.
     */
    public List<TraceEntry> forRequest(String requestId) {
        return query(e -> requestId.equals(e.requestId));
    }

    /**
     * Get mutation history for a specific heap key.
     */
    public List<TraceEntry> forKey(String key) {
        return query(e -> key.equals(e.key));
    }

    /**
     * Get all trace entries for a specific function.
     */
    public List<TraceEntry> forFunction(String functionName) {
        return query(e -> functionName.equals(e.function));
    }

    /**
     * Get recent entries (newest first).
     */
    public List<TraceEntry> recent(int limit) {
        var entries = new ArrayList<TraceEntry>();
        int pos = writePos.get();
        for (int i = 0; i < Math.min(limit, capacity); i++) {
            int idx = (pos - 1 - i + capacity) % capacity;
            TraceEntry entry = ring[idx];
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    /**
     * Get entries between two timestamps.
     */
    public List<TraceEntry> between(Instant from, Instant to) {
        return query(e -> !e.timestamp.isBefore(from) && !e.timestamp.isAfter(to));
    }

    /**
     * Compute a snapshot diff: what keys changed between two timestamps.
     */
    public Map<String, Object> snapshotDiff(Instant from, Instant to) {
        var entries = between(from, to);
        var published = new LinkedHashMap<String, TraceEntry>();
        var removed = new ArrayList<String>();
        var reads = new LinkedHashMap<String, Integer>();

        for (var e : entries) {
            switch (e.operation) {
                case PUBLISH -> published.put(e.key, e);
                case REMOVE -> removed.add(e.key);
                case GET_HIT, GET_MISS -> reads.merge(e.key, 1, Integer::sum);
            }
        }

        return Map.of(
                "from", from.toString(),
                "to", to.toString(),
                "entriesInWindow", entries.size(),
                "keysPublished", published.keySet().stream().toList(),
                "keysRemoved", removed,
                "keysRead", reads,
                "publishDetails", published.values().stream()
                        .map(TraceEntry::toMap).toList()
        );
    }

    /**
     * Generic query — scans the ring buffer.
     */
    private List<TraceEntry> query(Predicate<TraceEntry> predicate) {
        var results = new ArrayList<TraceEntry>();
        for (int i = 0; i < capacity; i++) {
            TraceEntry entry = ring[i];
            if (entry != null && predicate.test(entry)) {
                results.add(entry);
            }
        }
        results.sort(Comparator.comparingLong(e -> e.nanoTime));
        return results;
    }

    public Map<String, Object> status() {
        long total = totalEntries.get();
        int filled = (int) Math.min(total, capacity);

        // Count by operation type
        var byCounts = new EnumMap<Operation, Integer>(Operation.class);
        for (int i = 0; i < capacity; i++) {
            TraceEntry entry = ring[i];
            if (entry != null) {
                byCounts.merge(entry.operation, 1, Integer::sum);
            }
        }

        // Unique keys
        var uniqueKeys = new HashSet<String>();
        for (int i = 0; i < capacity; i++) {
            TraceEntry entry = ring[i];
            if (entry != null) uniqueKeys.add(entry.key);
        }

        return Map.of(
                "capacity", capacity,
                "filled", filled,
                "totalEntries", total,
                "byOperation", byCounts,
                "uniqueKeys", uniqueKeys.size()
        );
    }

    // ── Types ──

    public enum Operation {
        PUBLISH,
        GET_HIT,
        GET_MISS,
        REMOVE
    }

    public record TraceEntry(
            long nanoTime,
            Instant timestamp,
            Operation operation,
            String key,
            String typeName,
            String group,
            String function,
            long version,
            String requestId,
            String threadName
    ) {
        public Map<String, Object> toMap() {
            return Map.of(
                    "time", timestamp.toString(),
                    "op", operation.name(),
                    "key", key,
                    "type", typeName,
                    "group", group,
                    "function", function,
                    "version", version,
                    "requestId", requestId != null ? requestId : "",
                    "thread", threadName
            );
        }
    }
}

package com.kubefn.runtime.memory;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-group GC pressure monitoring.
 *
 * Tracks GC activity and correlates it with function group behavior.
 * Reports via admin API and Prometheus metrics.
 *
 * Key metrics:
 *   - JVM-wide GC count and time
 *   - Per-group allocation rate (bytes/sec)
 *   - Per-group heap contribution estimate
 *   - GC pause ratio (time in GC / wall time)
 *   - Memory pool utilization (Eden, Old Gen, etc.)
 */
public class GCPressureMonitor {
    private static final Logger log = LoggerFactory.getLogger(GCPressureMonitor.class);

    private final GroupMemoryBudget budget;
    private final ScheduledExecutorService scheduler;
    private final long sampleIntervalMs;

    // Snapshot tracking
    private volatile GCSnapshot lastSnapshot;
    private volatile GCSnapshot currentSnapshot;
    private final ConcurrentHashMap<String, AllocationRate> groupAllocationRates = new ConcurrentHashMap<>();

    // Alert thresholds
    private double gcPauseRatioWarn = 0.05;    // 5% time in GC
    private double gcPauseRatioCritical = 0.15; // 15% time in GC
    private double heapPressureWarn = 0.80;
    private double heapPressureCritical = 0.95;

    public GCPressureMonitor(GroupMemoryBudget budget) {
        this(budget, 10_000); // sample every 10 seconds
    }

    public GCPressureMonitor(GroupMemoryBudget budget, long sampleIntervalMs) {
        this.budget = budget;
        this.sampleIntervalMs = sampleIntervalMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kubefn-gc-monitor");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start monitoring.
     */
    public void start() {
        lastSnapshot = captureSnapshot();
        scheduler.scheduleAtFixedRate(this::sample, sampleIntervalMs, sampleIntervalMs, TimeUnit.MILLISECONDS);
        log.info("GC pressure monitor started (interval: {}ms)", sampleIntervalMs);
    }

    /**
     * Stop monitoring.
     */
    public void stop() {
        scheduler.shutdown();
    }

    private void sample() {
        try {
            currentSnapshot = captureSnapshot();

            if (lastSnapshot != null) {
                long deltaTimeMs = currentSnapshot.timestampMs - lastSnapshot.timestampMs;
                long deltaGcTimeMs = currentSnapshot.totalGcTimeMs - lastSnapshot.totalGcTimeMs;
                double gcPauseRatio = deltaTimeMs > 0 ? (double) deltaGcTimeMs / deltaTimeMs : 0;

                double heapPressure = currentSnapshot.heapMaxBytes > 0
                        ? (double) currentSnapshot.heapUsedBytes / currentSnapshot.heapMaxBytes : 0;

                // Check thresholds
                if (gcPauseRatio >= gcPauseRatioCritical) {
                    log.error("GC PRESSURE CRITICAL: {}% of time in GC (threshold: {}%)",
                            String.format("%.1f", gcPauseRatio * 100),
                            String.format("%.1f", gcPauseRatioCritical * 100));
                } else if (gcPauseRatio >= gcPauseRatioWarn) {
                    log.warn("GC pressure warning: {}% of time in GC",
                            String.format("%.1f", gcPauseRatio * 100));
                }

                if (heapPressure >= heapPressureCritical) {
                    log.error("HEAP PRESSURE CRITICAL: {}% used ({}MB / {}MB)",
                            String.format("%.1f", heapPressure * 100),
                            currentSnapshot.heapUsedBytes / (1024 * 1024),
                            currentSnapshot.heapMaxBytes / (1024 * 1024));
                } else if (heapPressure >= heapPressureWarn) {
                    log.warn("Heap pressure warning: {}% used",
                            String.format("%.1f", heapPressure * 100));
                }
            }

            lastSnapshot = currentSnapshot;
        } catch (Exception e) {
            log.error("GC monitor sample failed: {}", e.getMessage());
        }
    }

    /**
     * Record an allocation for a group (called on heap publish).
     */
    public void recordGroupAllocation(String groupName, long bytes) {
        groupAllocationRates
                .computeIfAbsent(groupName, k -> new AllocationRate())
                .record(bytes);
    }

    /**
     * Get current status for admin API.
     */
    public Map<String, Object> getStatus() {
        GCSnapshot snap = currentSnapshot != null ? currentSnapshot : captureSnapshot();
        var status = new LinkedHashMap<String, Object>();

        // JVM-wide
        status.put("heapUsedMB", snap.heapUsedBytes / (1024 * 1024));
        status.put("heapMaxMB", snap.heapMaxBytes / (1024 * 1024));
        status.put("heapPressure", String.format("%.1f%%",
                snap.heapMaxBytes > 0 ? (double) snap.heapUsedBytes / snap.heapMaxBytes * 100 : 0));
        status.put("gcCount", snap.totalGcCount);
        status.put("gcTimeMs", snap.totalGcTimeMs);

        // GC pause ratio
        if (lastSnapshot != null && currentSnapshot != null) {
            long deltaTime = currentSnapshot.timestampMs - lastSnapshot.timestampMs;
            long deltaGc = currentSnapshot.totalGcTimeMs - lastSnapshot.totalGcTimeMs;
            double pauseRatio = deltaTime > 0 ? (double) deltaGc / deltaTime : 0;
            status.put("gcPauseRatio", String.format("%.3f%%", pauseRatio * 100));
            status.put("level", pauseRatio >= gcPauseRatioCritical ? "CRITICAL"
                    : pauseRatio >= gcPauseRatioWarn ? "WARNING" : "OK");
        }

        // Memory pools
        var pools = new LinkedHashMap<String, Object>();
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == java.lang.management.MemoryType.HEAP) {
                var usage = pool.getUsage();
                pools.put(pool.getName(), Map.of(
                        "usedMB", usage.getUsed() / (1024 * 1024),
                        "maxMB", usage.getMax() > 0 ? usage.getMax() / (1024 * 1024) : -1,
                        "utilization", usage.getMax() > 0
                                ? String.format("%.1f%%", (double) usage.getUsed() / usage.getMax() * 100) : "N/A"
                ));
            }
        }
        status.put("memoryPools", pools);

        // Per-group allocation rates
        var groupRates = new LinkedHashMap<String, Object>();
        for (var entry : groupAllocationRates.entrySet()) {
            AllocationRate rate = entry.getValue();
            groupRates.put(entry.getKey(), Map.of(
                    "totalAllocatedMB", rate.totalBytes.get() / (1024 * 1024),
                    "allocations", rate.count.get(),
                    "avgAllocationBytes", rate.count.get() > 0 ? rate.totalBytes.get() / rate.count.get() : 0
            ));
        }
        status.put("groupAllocationRates", groupRates);

        // Merge per-group budget info
        status.put("groupBudgets", budget.getStatus().get("groups"));

        return status;
    }

    /**
     * Prometheus metrics text.
     */
    public String prometheusText() {
        GCSnapshot snap = currentSnapshot != null ? currentSnapshot : captureSnapshot();
        var sb = new StringBuilder();

        sb.append("# HELP kubefn_jvm_heap_used_bytes JVM heap used bytes\n");
        sb.append("# TYPE kubefn_jvm_heap_used_bytes gauge\n");
        sb.append(String.format("kubefn_jvm_heap_used_bytes %d\n", snap.heapUsedBytes));

        sb.append("# HELP kubefn_jvm_heap_max_bytes JVM heap max bytes\n");
        sb.append("# TYPE kubefn_jvm_heap_max_bytes gauge\n");
        sb.append(String.format("kubefn_jvm_heap_max_bytes %d\n", snap.heapMaxBytes));

        sb.append("# HELP kubefn_jvm_gc_count_total Total GC collection count\n");
        sb.append("# TYPE kubefn_jvm_gc_count_total counter\n");
        sb.append(String.format("kubefn_jvm_gc_count_total %d\n", snap.totalGcCount));

        sb.append("# HELP kubefn_jvm_gc_time_ms_total Total GC collection time in ms\n");
        sb.append("# TYPE kubefn_jvm_gc_time_ms_total counter\n");
        sb.append(String.format("kubefn_jvm_gc_time_ms_total %d\n", snap.totalGcTimeMs));

        sb.append("# HELP kubefn_group_allocated_bytes_total Total bytes allocated per group\n");
        sb.append("# TYPE kubefn_group_allocated_bytes_total counter\n");
        for (var entry : groupAllocationRates.entrySet()) {
            sb.append(String.format("kubefn_group_allocated_bytes_total{group=\"%s\"} %d\n",
                    entry.getKey(), entry.getValue().totalBytes.get()));
        }

        return sb.toString();
    }

    private GCSnapshot captureSnapshot() {
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long gcCount = 0, gcTime = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += gc.getCollectionCount();
            gcTime += gc.getCollectionTime();
        }
        return new GCSnapshot(
                System.currentTimeMillis(),
                heap.getUsed(), heap.getMax(),
                gcCount, gcTime
        );
    }

    private record GCSnapshot(
            long timestampMs,
            long heapUsedBytes, long heapMaxBytes,
            long totalGcCount, long totalGcTimeMs
    ) {}

    private static class AllocationRate {
        final AtomicLong totalBytes = new AtomicLong(0);
        final AtomicLong count = new AtomicLong(0);

        void record(long bytes) {
            totalBytes.addAndGet(bytes);
            count.incrementAndGet();
        }
    }
}

package com.kubefn.runtime.memory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-group memory budget tracking and enforcement.
 *
 * Each function group gets a configurable memory budget. The runtime tracks
 * estimated heap usage per group (via heap publish sizes) and enforces limits.
 *
 * When a group exceeds its budget:
 * - WARN at 80% → log warning, emit metric
 * - SOFT_LIMIT at 90% → reject new heap publishes, allow reads
 * - HARD_LIMIT at 100% → trigger MemoryCircuitBreaker to kill/restart the group
 */
public class GroupMemoryBudget {
    private static final Logger log = LoggerFactory.getLogger(GroupMemoryBudget.class);

    private final long defaultBudgetBytes;
    private final ConcurrentHashMap<String, Long> groupBudgets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> groupUsage = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> groupPublishCount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> groupRejectCount = new ConcurrentHashMap<>();
    private final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();

    public GroupMemoryBudget(long defaultBudgetBytes) {
        this.defaultBudgetBytes = defaultBudgetBytes;
    }

    public GroupMemoryBudget() {
        this(256 * 1024 * 1024); // 256MB default per group
    }

    /**
     * Set a custom memory budget for a specific group.
     */
    public void setBudget(String groupName, long budgetBytes) {
        groupBudgets.put(groupName, budgetBytes);
        log.info("Memory budget for group '{}': {}MB", groupName, budgetBytes / (1024 * 1024));
    }

    /**
     * Get the budget for a group (custom or default).
     */
    public long getBudget(String groupName) {
        return groupBudgets.getOrDefault(groupName, defaultBudgetBytes);
    }

    /**
     * Record memory usage when a group publishes to HeapExchange.
     * Returns the enforcement level.
     */
    public MemoryLevel recordAllocation(String groupName, long estimatedBytes) {
        AtomicLong usage = groupUsage.computeIfAbsent(groupName, k -> new AtomicLong(0));
        groupPublishCount.computeIfAbsent(groupName, k -> new AtomicLong(0)).incrementAndGet();

        long newUsage = usage.addAndGet(estimatedBytes);
        long budget = getBudget(groupName);
        double ratio = (double) newUsage / budget;

        if (ratio >= 1.0) {
            log.error("Group '{}' EXCEEDED memory budget: {}MB / {}MB ({}%)",
                    groupName, newUsage / (1024 * 1024), budget / (1024 * 1024),
                    String.format("%.1f", ratio * 100));
            return MemoryLevel.HARD_LIMIT;
        } else if (ratio >= 0.9) {
            groupRejectCount.computeIfAbsent(groupName, k -> new AtomicLong(0)).incrementAndGet();
            log.warn("Group '{}' at SOFT LIMIT: {}MB / {}MB ({}%) — new publishes rejected",
                    groupName, newUsage / (1024 * 1024), budget / (1024 * 1024),
                    String.format("%.1f", ratio * 100));
            // Roll back the allocation
            usage.addAndGet(-estimatedBytes);
            return MemoryLevel.SOFT_LIMIT;
        } else if (ratio >= 0.8) {
            log.warn("Group '{}' memory WARNING: {}MB / {}MB ({}%)",
                    groupName, newUsage / (1024 * 1024), budget / (1024 * 1024),
                    String.format("%.1f", ratio * 100));
            return MemoryLevel.WARN;
        }
        return MemoryLevel.OK;
    }

    /**
     * Record memory release when objects are removed from heap.
     */
    public void recordDeallocation(String groupName, long estimatedBytes) {
        AtomicLong usage = groupUsage.get(groupName);
        if (usage != null) {
            usage.addAndGet(-Math.min(estimatedBytes, usage.get()));
        }
    }

    /**
     * Get current usage for a group.
     */
    public long getUsage(String groupName) {
        AtomicLong usage = groupUsage.get(groupName);
        return usage != null ? usage.get() : 0;
    }

    /**
     * Get usage ratio (0.0 to 1.0+).
     */
    public double getUsageRatio(String groupName) {
        return (double) getUsage(groupName) / getBudget(groupName);
    }

    /**
     * Reset usage tracking for a group (called on group unload/restart).
     */
    public void resetGroup(String groupName) {
        groupUsage.remove(groupName);
        groupPublishCount.remove(groupName);
        groupRejectCount.remove(groupName);
        log.info("Memory tracking reset for group '{}'", groupName);
    }

    /**
     * Get JVM-wide GC pressure metrics.
     */
    public GCPressure getGCPressure() {
        var heapUsage = memoryMXBean.getHeapMemoryUsage();
        long used = heapUsage.getUsed();
        long max = heapUsage.getMax();
        double pressure = max > 0 ? (double) used / max : 0;

        var gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        long totalGcCount = 0;
        long totalGcTimeMs = 0;
        for (var gc : gcBeans) {
            totalGcCount += gc.getCollectionCount();
            totalGcTimeMs += gc.getCollectionTime();
        }

        return new GCPressure(used, max, pressure, totalGcCount, totalGcTimeMs);
    }

    /**
     * Get status for admin API.
     */
    public Map<String, Object> getStatus() {
        var status = new java.util.LinkedHashMap<String, Object>();

        var gcPressure = getGCPressure();
        status.put("jvmHeapUsedMB", gcPressure.usedBytes() / (1024 * 1024));
        status.put("jvmHeapMaxMB", gcPressure.maxBytes() / (1024 * 1024));
        status.put("jvmHeapPressure", String.format("%.1f%%", gcPressure.pressure() * 100));
        status.put("gcCount", gcPressure.gcCount());
        status.put("gcTimeMs", gcPressure.gcTimeMs());
        status.put("defaultBudgetMB", defaultBudgetBytes / (1024 * 1024));

        var groups = new java.util.LinkedHashMap<String, Object>();
        for (String group : groupUsage.keySet()) {
            long usage = getUsage(group);
            long budget = getBudget(group);
            var groupInfo = new java.util.LinkedHashMap<String, Object>();
            groupInfo.put("usageMB", usage / (1024 * 1024));
            groupInfo.put("budgetMB", budget / (1024 * 1024));
            groupInfo.put("usagePercent", String.format("%.1f%%", (double) usage / budget * 100));
            groupInfo.put("publishes", groupPublishCount.getOrDefault(group, new AtomicLong(0)).get());
            groupInfo.put("rejects", groupRejectCount.getOrDefault(group, new AtomicLong(0)).get());
            groupInfo.put("level", getLevel(group).name());
            groups.put(group, groupInfo);
        }
        status.put("groups", groups);

        return status;
    }

    private MemoryLevel getLevel(String groupName) {
        double ratio = getUsageRatio(groupName);
        if (ratio >= 1.0) return MemoryLevel.HARD_LIMIT;
        if (ratio >= 0.9) return MemoryLevel.SOFT_LIMIT;
        if (ratio >= 0.8) return MemoryLevel.WARN;
        return MemoryLevel.OK;
    }

    public enum MemoryLevel {
        OK,
        WARN,
        SOFT_LIMIT,
        HARD_LIMIT
    }

    public record GCPressure(
            long usedBytes,
            long maxBytes,
            double pressure,
            long gcCount,
            long gcTimeMs
    ) {}
}

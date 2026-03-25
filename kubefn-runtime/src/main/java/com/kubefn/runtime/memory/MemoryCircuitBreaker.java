package com.kubefn.runtime.memory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Memory-aware circuit breaker that kills/restarts function groups
 * when they exceed memory thresholds.
 *
 * Unlike request-based circuit breakers (which trip on errors),
 * this trips on MEMORY pressure — the #1 concern with shared-JVM architecture.
 *
 * States per group:
 *   CLOSED  → normal operation
 *   TRIPPED → group exceeded hard limit, classloader will be unloaded
 *   COOLING → group was restarted, in cooldown period before accepting full load
 *
 * When tripped:
 *   1. All new requests to the group are rejected (503)
 *   2. In-flight requests are drained
 *   3. Classloader is unloaded (releases all group memory)
 *   4. Group memory budget is reset
 *   5. After cooldown, group can be reloaded
 */
public class MemoryCircuitBreaker {
    private static final Logger log = LoggerFactory.getLogger(MemoryCircuitBreaker.class);

    private final GroupMemoryBudget budget;
    private final ConcurrentHashMap<String, BreakerState> groupStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> tripCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastTripTime = new ConcurrentHashMap<>();
    private final long cooldownMs;
    private volatile GroupUnloader unloader;

    public MemoryCircuitBreaker(GroupMemoryBudget budget) {
        this(budget, 30_000); // 30 second cooldown default
    }

    public MemoryCircuitBreaker(GroupMemoryBudget budget, long cooldownMs) {
        this.budget = budget;
        this.cooldownMs = cooldownMs;
    }

    /**
     * Set the callback for unloading a group's classloader.
     */
    public void setGroupUnloader(GroupUnloader unloader) {
        this.unloader = unloader;
    }

    /**
     * Check if a group is allowed to process requests.
     */
    public boolean isAllowed(String groupName) {
        BreakerState state = groupStates.getOrDefault(groupName, BreakerState.CLOSED);
        if (state == BreakerState.CLOSED) return true;
        if (state == BreakerState.COOLING) {
            Long tripTime = lastTripTime.get(groupName);
            if (tripTime != null && System.currentTimeMillis() - tripTime > cooldownMs) {
                groupStates.put(groupName, BreakerState.CLOSED);
                log.info("Memory breaker CLOSED for group '{}' after cooldown", groupName);
                return true;
            }
            return false;
        }
        return false; // TRIPPED
    }

    /**
     * Called after every heap publish to check if the group should be tripped.
     */
    public void checkAndTrip(String groupName, GroupMemoryBudget.MemoryLevel level) {
        if (level == GroupMemoryBudget.MemoryLevel.HARD_LIMIT) {
            trip(groupName);
        }
    }

    /**
     * Trip the breaker for a group — unload its classloader and reset memory.
     */
    public void trip(String groupName) {
        BreakerState current = groupStates.get(groupName);
        if (current == BreakerState.TRIPPED) return; // already tripping

        groupStates.put(groupName, BreakerState.TRIPPED);
        tripCounts.computeIfAbsent(groupName, k -> new AtomicInteger(0)).incrementAndGet();
        lastTripTime.put(groupName, System.currentTimeMillis());

        log.error("MEMORY CIRCUIT BREAKER TRIPPED for group '{}' — usage: {}MB / {}MB",
                groupName,
                budget.getUsage(groupName) / (1024 * 1024),
                budget.getBudget(groupName) / (1024 * 1024));

        // Unload the group's classloader to free memory
        if (unloader != null) {
            try {
                unloader.unloadGroup(groupName);
                log.info("Group '{}' classloader unloaded by memory circuit breaker", groupName);
            } catch (Exception e) {
                log.error("Failed to unload group '{}': {}", groupName, e.getMessage());
            }
        }

        // Reset memory tracking
        budget.resetGroup(groupName);

        // Move to cooling state
        groupStates.put(groupName, BreakerState.COOLING);
        log.info("Group '{}' in COOLING state for {}ms", groupName, cooldownMs);
    }

    /**
     * Get status for admin API.
     */
    public Map<String, Object> getStatus() {
        var status = new java.util.LinkedHashMap<String, Object>();
        var groups = new java.util.LinkedHashMap<String, Object>();

        for (String group : groupStates.keySet()) {
            var info = new java.util.LinkedHashMap<String, Object>();
            info.put("state", groupStates.getOrDefault(group, BreakerState.CLOSED).name());
            info.put("tripCount", tripCounts.getOrDefault(group, new AtomicInteger(0)).get());
            Long tripTime = lastTripTime.get(group);
            if (tripTime != null) {
                info.put("lastTripAt", tripTime);
                info.put("cooldownRemainingMs",
                        Math.max(0, cooldownMs - (System.currentTimeMillis() - tripTime)));
            }
            groups.put(group, info);
        }

        status.put("cooldownMs", cooldownMs);
        status.put("groups", groups);
        return status;
    }

    /**
     * Prometheus metrics text.
     */
    public String prometheusText() {
        var sb = new StringBuilder();
        sb.append("# HELP kubefn_memory_breaker_trips_total Total memory circuit breaker trips per group\n");
        sb.append("# TYPE kubefn_memory_breaker_trips_total counter\n");
        for (var entry : tripCounts.entrySet()) {
            sb.append(String.format("kubefn_memory_breaker_trips_total{group=\"%s\"} %d\n",
                    entry.getKey(), entry.getValue().get()));
        }
        return sb.toString();
    }

    public enum BreakerState {
        CLOSED,
        TRIPPED,
        COOLING
    }

    @FunctionalInterface
    public interface GroupUnloader {
        void unloadGroup(String groupName) throws Exception;
    }
}

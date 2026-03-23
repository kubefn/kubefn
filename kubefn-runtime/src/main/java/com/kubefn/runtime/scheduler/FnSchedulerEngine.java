package com.kubefn.runtime.scheduler;

import com.kubefn.api.FnSchedule;
import com.kubefn.api.KubeFnHandler;
import com.kubefn.api.KubeFnRequest;
import com.kubefn.api.FnGroup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scans loaded functions for @FnSchedule annotations and executes them
 * on a cron schedule using virtual threads. Fires a check every minute.
 */
public class FnSchedulerEngine {

    private static final Logger log = LoggerFactory.getLogger(FnSchedulerEngine.class);

    private final ScheduledExecutorService scheduler;
    private final Map<String, ScheduledFunctionEntry> entries = new ConcurrentHashMap<>();
    private final Set<String> currentlyRunning = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean started = new AtomicBoolean(false);

    public FnSchedulerEngine() {
        this.scheduler = Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory());
    }

    /**
     * Register a handler that has an @FnSchedule annotation.
     */
    public void register(String functionName, KubeFnHandler handler) {
        FnSchedule schedule = handler.getClass().getAnnotation(FnSchedule.class);
        if (schedule == null) {
            return;
        }

        String groupName = "";
        FnGroup group = handler.getClass().getAnnotation(FnGroup.class);
        if (group != null) {
            groupName = group.value();
        }

        ScheduledFunctionEntry entry = new ScheduledFunctionEntry(
            functionName, groupName, handler, schedule.cron(),
            schedule.timezone(), schedule.skipIfRunning(),
            schedule.timeoutMs(), schedule.runOnStart()
        );
        entries.put(functionName, entry);
        log.info("Registered scheduled function: {} with cron [{}] timezone [{}]",
                functionName, schedule.cron(), schedule.timezone());
    }

    /**
     * Start the scheduler. Fires runOnStart functions immediately, then ticks every minute.
     */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            log.warn("Scheduler already started");
            return;
        }

        log.info("Starting FnSchedulerEngine with {} registered functions", entries.size());

        // Fire runOnStart functions immediately
        for (ScheduledFunctionEntry entry : entries.values()) {
            if (entry.runOnStart) {
                log.info("Firing runOnStart for function: {}", entry.functionName);
                executeFunction(entry);
            }
        }

        // Tick every 60 seconds, aligned roughly to the minute
        long initialDelay = 60 - (System.currentTimeMillis() / 1000 % 60);
        scheduler.scheduleAtFixedRate(this::tick, initialDelay, 60, TimeUnit.SECONDS);
    }

    /**
     * Shut down the scheduler and wait for running tasks.
     */
    public void shutdown() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        log.info("Shutting down FnSchedulerEngine");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Returns admin data about all scheduled functions.
     */
    public SchedulerAdminData getScheduledFunctions() {
        List<SchedulerAdminData.ScheduledEntry> adminEntries = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (ScheduledFunctionEntry entry : entries.values()) {
            long nextFire = computeNextFireMs(entry.cron, entry.timezone, now);
            adminEntries.add(new SchedulerAdminData.ScheduledEntry(
                entry.functionName, entry.groupName, entry.cron,
                entry.timezone, currentlyRunning.contains(entry.functionName),
                entry.lastRunMs, nextFire,
                entry.totalRuns.get(), entry.errors.get()
            ));
        }

        return new SchedulerAdminData(Collections.unmodifiableList(adminEntries));
    }

    private void tick() {
        ZonedDateTime now = ZonedDateTime.now();
        for (ScheduledFunctionEntry entry : entries.values()) {
            try {
                ZonedDateTime zoned = now.withZoneSameInstant(ZoneId.of(entry.timezone));
                if (cronMatches(entry.cron, zoned)) {
                    executeFunction(entry);
                }
            } catch (Exception e) {
                log.error("Error checking schedule for function: {}", entry.functionName, e);
            }
        }
    }

    private void executeFunction(ScheduledFunctionEntry entry) {
        if (entry.skipIfRunning && currentlyRunning.contains(entry.functionName)) {
            log.debug("Skipping function {} — still running from previous invocation", entry.functionName);
            return;
        }

        Thread.ofVirtual().name("sched-" + entry.functionName).start(() -> {
            currentlyRunning.add(entry.functionName);
            entry.totalRuns.incrementAndGet();
            long startTime = System.currentTimeMillis();

            Future<?> task = null;
            try {
                // Use a separate virtual thread so we can enforce timeout
                var executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
                task = executor.submit(() -> {
                    try {
                        KubeFnRequest syntheticRequest = createSyntheticRequest(entry.functionName);
                        log.info("Executing scheduled function: {}", entry.functionName);
                        entry.handler.handle(syntheticRequest);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

                if (entry.timeoutMs > 0) {
                    task.get(entry.timeoutMs, TimeUnit.MILLISECONDS);
                } else {
                    task.get();
                }

                long elapsed = System.currentTimeMillis() - startTime;
                log.info("Scheduled function {} completed in {}ms", entry.functionName, elapsed);
                executor.shutdown();
            } catch (java.util.concurrent.TimeoutException e) {
                log.warn("Scheduled function {} timed out after {}ms", entry.functionName, entry.timeoutMs);
                if (task != null) {
                    task.cancel(true);
                }
                entry.errors.incrementAndGet();
            } catch (Exception e) {
                log.error("Scheduled function {} failed", entry.functionName, e);
                entry.errors.incrementAndGet();
            } finally {
                entry.lastRunMs = System.currentTimeMillis();
                currentlyRunning.remove(entry.functionName);
            }
        });
    }

    private KubeFnRequest createSyntheticRequest(String functionName) {
        return new KubeFnRequest(
            "SCHEDULE",
            "/scheduled/" + functionName,
            "",
            Map.of("X-KubeFn-Trigger", "scheduler"),
            Map.of(),
            new byte[0]
        );
    }

    /**
     * Simple 5-field cron matcher: minute hour day-of-month month day-of-week.
     * Supports numeric values, '*' (any), and comma-separated lists.
     */
    static boolean cronMatches(String cron, ZonedDateTime time) {
        String[] fields = cron.trim().split("\\s+");
        if (fields.length != 5) {
            throw new IllegalArgumentException("Cron expression must have 5 fields: " + cron);
        }

        return fieldMatches(fields[0], time.getMinute())
            && fieldMatches(fields[1], time.getHour())
            && fieldMatches(fields[2], time.getDayOfMonth())
            && fieldMatches(fields[3], time.getMonthValue())
            && fieldMatches(fields[4], time.getDayOfWeek().getValue() % 7); // 0=Sunday
    }

    private static boolean fieldMatches(String field, int value) {
        if ("*".equals(field)) {
            return true;
        }
        // Handle step values like */5
        if (field.startsWith("*/")) {
            int step = Integer.parseInt(field.substring(2));
            return value % step == 0;
        }
        // Handle comma-separated values
        for (String part : field.split(",")) {
            // Handle ranges like 1-5
            if (part.contains("-")) {
                String[] range = part.split("-");
                int low = Integer.parseInt(range[0]);
                int high = Integer.parseInt(range[1]);
                if (value >= low && value <= high) {
                    return true;
                }
            } else {
                if (Integer.parseInt(part) == value) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Compute the approximate next fire time in epoch millis.
     * Iterates minute-by-minute up to 48 hours ahead.
     */
    private long computeNextFireMs(String cron, String timezone, long fromMs) {
        ZonedDateTime time = Instant.ofEpochMilli(fromMs)
            .atZone(ZoneId.of(timezone))
            .plusMinutes(1)
            .withSecond(0)
            .withNano(0);

        int maxIterations = 48 * 60; // 48 hours of minutes
        for (int i = 0; i < maxIterations; i++) {
            if (cronMatches(cron, time)) {
                return time.toInstant().toEpochMilli();
            }
            time = time.plusMinutes(1);
        }
        return -1; // Could not determine next fire time
    }

    private static class ScheduledFunctionEntry {
        final String functionName;
        final String groupName;
        final KubeFnHandler handler;
        final String cron;
        final String timezone;
        final boolean skipIfRunning;
        final long timeoutMs;
        final boolean runOnStart;
        final AtomicInteger totalRuns = new AtomicInteger(0);
        final AtomicInteger errors = new AtomicInteger(0);
        volatile long lastRunMs = 0;

        ScheduledFunctionEntry(String functionName, String groupName, KubeFnHandler handler,
                               String cron, String timezone, boolean skipIfRunning,
                               long timeoutMs, boolean runOnStart) {
            this.functionName = functionName;
            this.groupName = groupName;
            this.handler = handler;
            this.cron = cron;
            this.timezone = timezone;
            this.skipIfRunning = skipIfRunning;
            this.timeoutMs = timeoutMs;
            this.runOnStart = runOnStart;
        }
    }
}

package com.kubefn.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a function to run on a cron schedule inside the living organism.
 *
 * <p>Replaces Kubernetes CronJobs with zero-overhead scheduled execution:
 * <ul>
 *   <li>No container build per job</li>
 *   <li>No cold start (JVM already warm)</li>
 *   <li>Access to HeapExchange (shared state with HTTP functions)</li>
 *   <li>Hot-swappable (update schedule logic without restart)</li>
 * </ul>
 *
 * <p>The function can also have {@link FnRoute} for HTTP triggering —
 * enabling both scheduled AND on-demand execution.
 *
 * <h3>Example:</h3>
 * <pre>{@code
 * @FnRoute(path = "/cleanup/sessions", methods = {"POST"})
 * @FnGroup("maintenance")
 * &#64;FnSchedule(cron = "0 0/15 * * *", timezone = "UTC")
 * public class SessionCleanup implements KubeFnHandler, FnContextAware {
 *     public KubeFnResponse handle(KubeFnRequest request) {
 *         // Runs every 15 minutes AND can be triggered via HTTP
 *         int cleaned = cleanStaleSessions(ctx.heap());
 *         return KubeFnResponse.ok(Map.of("cleaned", cleaned));
 *     }
 * }
 * }</pre>
 *
 * <h3>Cron expression format (standard 5-field):</h3>
 * <pre>
 * ┌───────── minute (0-59)
 * │ ┌───────── hour (0-23)
 * │ │ ┌───────── day of month (1-31)
 * │ │ │ ┌───────── month (1-12)
 * │ │ │ │ ┌───────── day of week (0-6, Sun=0)
 * │ │ │ │ │
 * * * * * *
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface FnSchedule {

    /**
     * Cron expression (5-field standard format).
     * Examples: "0 * * * *" (hourly), "0 0 * * *" (daily), "0/5 * * * *" (every 5 min)
     */
    String cron();

    /**
     * Timezone for schedule evaluation. Default: UTC.
     */
    String timezone() default "UTC";

    /**
     * Whether to run immediately on function load (in addition to schedule).
     * Useful for cache warmers that should populate data on startup.
     */
    boolean runOnStart() default false;

    /**
     * Maximum execution time in milliseconds before the scheduled run is cancelled.
     * Default: 60000 (1 minute).
     */
    long timeoutMs() default 60000;

    /**
     * If true, skip this execution if the previous one is still running.
     * Prevents overlapping executions for long-running jobs.
     */
    boolean skipIfRunning() default true;
}

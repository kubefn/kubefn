package com.kubefn.runtime.resources;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Shared resource manager for the organism. Manages connection pools,
 * HTTP clients, and external resources that functions share.
 *
 * <p>In microservices, each service owns its own connection pool.
 * In KubeFn, the organism owns the pools and functions borrow from them.
 * This prevents connection explosion (N functions × M connections)
 * and enables per-function concurrency limits on shared resources.
 *
 * <h3>Design principles:</h3>
 * <ul>
 *   <li>Resources are registered once, shared by all functions</li>
 *   <li>Per-function concurrency limits prevent one function from starving others</li>
 *   <li>Resources are named and typed for discovery</li>
 *   <li>Lifecycle managed by the organism (close on shutdown)</li>
 * </ul>
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 * // Register a shared DataSource at organism startup
 * resourceManager.register("postgres-main", dataSource, DataSource.class);
 *
 * // Functions borrow it with concurrency limits
 * try (var lease = resourceManager.acquire("postgres-main", "my-function", 5000)) {
 *     DataSource ds = lease.resource(DataSource.class);
 *     // Use the DataSource
 * }
 * }</pre>
 */
public class SharedResourceManager {

    private static final Logger log = LoggerFactory.getLogger(SharedResourceManager.class);

    private final ConcurrentHashMap<String, ManagedResource<?>> resources = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Semaphore> perFunctionLimits = new ConcurrentHashMap<>();

    /**
     * Register a shared resource (connection pool, HTTP client, etc.)
     *
     * @param name unique resource name
     * @param resource the resource instance
     * @param type the resource type
     * @param maxConcurrency max concurrent users of this resource
     */
    public <T> void register(String name, T resource, Class<T> type, int maxConcurrency) {
        resources.put(name, new ManagedResource<>(name, resource, type,
                new Semaphore(maxConcurrency), maxConcurrency));
        log.info("Registered shared resource: {} (type={}, maxConcurrency={})",
                name, type.getSimpleName(), maxConcurrency);
    }

    /**
     * Acquire a lease on a shared resource with per-function concurrency control.
     *
     * @param resourceName the resource to acquire
     * @param functionName the function requesting access
     * @param timeoutMs max wait time
     * @return a ResourceLease that must be closed when done
     * @throws ResourceUnavailableException if timeout or resource not found
     */
    public ResourceLease acquire(String resourceName, String functionName, long timeoutMs)
            throws ResourceUnavailableException {

        ManagedResource<?> managed = resources.get(resourceName);
        if (managed == null) {
            throw new ResourceUnavailableException(
                    "Resource not found: " + resourceName);
        }

        // Per-function concurrency limit (prevents one function from hogging)
        String limitKey = resourceName + ":" + functionName;
        Semaphore functionLimit = perFunctionLimits.computeIfAbsent(limitKey,
                k -> new Semaphore(managed.maxConcurrency / 2 + 1)); // Each function gets at most half + 1

        try {
            if (!managed.semaphore.tryAcquire(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                throw new ResourceUnavailableException(
                        "Resource '" + resourceName + "' at max concurrency for function '" + functionName + "'");
            }

            if (!functionLimit.tryAcquire(0, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                managed.semaphore.release();
                throw new ResourceUnavailableException(
                        "Per-function concurrency limit reached for '" + functionName + "' on '" + resourceName + "'");
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResourceUnavailableException("Interrupted waiting for resource: " + resourceName);
        }

        managed.activeLeases.incrementAndGet();

        return new ResourceLease(managed, functionLimit);
    }

    /**
     * Get resource metadata for admin/discovery.
     */
    public Map<String, ResourceInfo> listResources() {
        var result = new ConcurrentHashMap<String, ResourceInfo>();
        resources.forEach((name, managed) -> {
            result.put(name, new ResourceInfo(
                    name,
                    managed.type.getSimpleName(),
                    managed.maxConcurrency,
                    managed.maxConcurrency - managed.semaphore.availablePermits(),
                    managed.activeLeases.get(),
                    managed.totalAcquired.get()
            ));
        });
        return result;
    }

    /**
     * Shutdown all managed resources.
     */
    public void shutdown() {
        resources.forEach((name, managed) -> {
            if (managed.resource instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                    log.info("Closed shared resource: {}", name);
                } catch (Exception e) {
                    log.warn("Error closing resource {}: {}", name, e.getMessage());
                }
            }
        });
        resources.clear();
    }

    // ─── Inner types ────────────────────────────────────────────

    static class ManagedResource<T> {
        final String name;
        final T resource;
        final Class<T> type;
        final Semaphore semaphore;
        final int maxConcurrency;
        final java.util.concurrent.atomic.AtomicInteger activeLeases = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicLong totalAcquired = new java.util.concurrent.atomic.AtomicLong(0);

        ManagedResource(String name, T resource, Class<T> type, Semaphore semaphore, int maxConcurrency) {
            this.name = name;
            this.resource = resource;
            this.type = type;
            this.semaphore = semaphore;
            this.maxConcurrency = maxConcurrency;
        }
    }

    /**
     * A lease on a shared resource. Must be closed when done.
     */
    public static class ResourceLease implements AutoCloseable {
        private final ManagedResource<?> managed;
        private final Semaphore functionLimit;
        private boolean released = false;

        ResourceLease(ManagedResource<?> managed, Semaphore functionLimit) {
            this.managed = managed;
            this.functionLimit = functionLimit;
            managed.totalAcquired.incrementAndGet();
        }

        @SuppressWarnings("unchecked")
        public <T> T resource(Class<T> type) {
            return (T) managed.resource;
        }

        @Override
        public void close() {
            if (!released) {
                released = true;
                managed.activeLeases.decrementAndGet();
                functionLimit.release();
                managed.semaphore.release();
            }
        }
    }

    public record ResourceInfo(
            String name, String type, int maxConcurrency,
            int currentActive, int activeLeases, long totalAcquired
    ) {}

    public static class ResourceUnavailableException extends Exception {
        public ResourceUnavailableException(String message) {
            super(message);
        }
    }
}

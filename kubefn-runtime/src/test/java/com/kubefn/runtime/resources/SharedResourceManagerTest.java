package com.kubefn.runtime.resources;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SharedResourceManagerTest {

    private SharedResourceManager manager;

    @BeforeEach
    void setUp() {
        manager = new SharedResourceManager();
    }

    @Test
    void registerAndAcquireResource() throws Exception {
        manager.register("test-pool", "my-datasource", String.class, 10);

        try (var lease = manager.acquire("test-pool", "my-function", 1000)) {
            String resource = lease.resource(String.class);
            assertEquals("my-datasource", resource);
        }
    }

    @Test
    void acquireUnknownResourceThrows() {
        assertThrows(SharedResourceManager.ResourceUnavailableException.class,
                () -> manager.acquire("nonexistent", "fn", 100));
    }

    @Test
    @Timeout(5)
    void concurrencyLimitEnforced() throws Exception {
        manager.register("limited-pool", "resource", String.class, 2);

        // Acquire 2 leases (at max)
        var lease1 = manager.acquire("limited-pool", "fn-a", 1000);
        var lease2 = manager.acquire("limited-pool", "fn-b", 1000);

        // Third should fail (timeout)
        assertThrows(SharedResourceManager.ResourceUnavailableException.class,
                () -> manager.acquire("limited-pool", "fn-c", 100));

        // Release one → third should work
        lease1.close();
        var lease3 = manager.acquire("limited-pool", "fn-c", 1000);
        lease3.close();
        lease2.close();
    }

    @Test
    void metricsTrackUsage() throws Exception {
        manager.register("pool", "resource", String.class, 10);

        try (var lease = manager.acquire("pool", "fn", 1000)) {
            var info = manager.listResources().get("pool");
            assertEquals(1, info.activeLeases());
            assertEquals(1, info.totalAcquired());
        }

        var info = manager.listResources().get("pool");
        assertEquals(0, info.activeLeases());
        assertEquals(1, info.totalAcquired());
    }

    @Test
    void shutdownClosesAutoCloseableResources() {
        var closeable = new AutoCloseable() {
            boolean closed = false;
            @Override public void close() { closed = true; }
            @Override public String toString() { return "test-closeable"; }
        };

        manager.register("closeable-pool", closeable, AutoCloseable.class, 5);
        manager.shutdown();

        assertTrue(closeable.closed, "Resource should be closed on shutdown");
    }

    @Test
    @Timeout(5)
    void perFunctionLimitPreventsHogging() throws Exception {
        // Pool has 10 max, but each function gets at most 6 (10/2 + 1)
        manager.register("shared-pool", "resource", String.class, 10);

        // Function A takes 6 leases
        var leases = new java.util.ArrayList<SharedResourceManager.ResourceLease>();
        for (int i = 0; i < 6; i++) {
            leases.add(manager.acquire("shared-pool", "fn-a", 1000));
        }

        // Function A's 7th should be blocked by per-function limit
        assertThrows(SharedResourceManager.ResourceUnavailableException.class,
                () -> manager.acquire("shared-pool", "fn-a", 100));

        // But Function B can still acquire (different function)
        var leaseB = manager.acquire("shared-pool", "fn-b", 1000);
        assertNotNull(leaseB);

        // Cleanup
        leases.forEach(SharedResourceManager.ResourceLease::close);
        leaseB.close();
    }
}

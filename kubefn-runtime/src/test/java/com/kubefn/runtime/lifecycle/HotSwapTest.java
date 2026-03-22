package com.kubefn.runtime.lifecycle;

import com.kubefn.api.KubeFnHandler;
import com.kubefn.api.KubeFnResponse;
import com.kubefn.runtime.heap.HeapExchangeImpl;
import com.kubefn.runtime.routing.FunctionEntry;
import com.kubefn.runtime.routing.FunctionRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Production hot-swap tests: verifies safe function replacement
 * under concurrent load with in-flight requests.
 */
class HotSwapTest {

    private DrainManager drainManager;
    private FunctionRouter router;

    @BeforeEach
    void setUp() {
        drainManager = new DrainManager();
        router = new FunctionRouter();
    }

    @Test
    @Timeout(10)
    void drainCompletesBeforeUnload() throws InterruptedException {
        // Simulate 10 in-flight requests
        for (int i = 0; i < 10; i++) {
            drainManager.acquireRequest("test-group");
        }
        assertEquals(10, drainManager.inFlightCount("test-group"));

        // Start drain in background
        AtomicBoolean drained = new AtomicBoolean(false);
        Thread drainThread = Thread.startVirtualThread(() ->
                drained.set(drainManager.drainAndWait("test-group", 5000)));

        Thread.sleep(100);
        assertFalse(drained.get()); // Should still be waiting

        // Complete 9 requests
        for (int i = 0; i < 9; i++) {
            drainManager.releaseRequest("test-group");
        }
        Thread.sleep(100);
        assertFalse(drained.get()); // Still 1 in-flight

        // Complete last request
        drainManager.releaseRequest("test-group");
        drainThread.join(2000);
        assertTrue(drained.get()); // Now drained
    }

    @Test
    @Timeout(10)
    void concurrentRequestsDuringDrain() throws InterruptedException {
        AtomicInteger completed = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);
        CountDownLatch allStarted = new CountDownLatch(1);

        // Start 50 concurrent "requests"
        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        for (int i = 0; i < 50; i++) {
            exec.submit(() -> {
                try { allStarted.await(); } catch (InterruptedException ignored) {}

                if (drainManager.acquireRequest("test-group")) {
                    try {
                        Thread.sleep(10); // Simulate 10ms request processing
                        completed.incrementAndGet();
                    } catch (InterruptedException ignored) {
                    } finally {
                        drainManager.releaseRequest("test-group");
                    }
                } else {
                    rejected.incrementAndGet(); // Rejected during drain
                }
            });
        }

        // Let all start
        allStarted.countDown();
        Thread.sleep(5); // Let some requests start

        // Initiate drain (some requests are in-flight)
        boolean drained = drainManager.drainAndWait("test-group", 5000);
        assertTrue(drained);

        exec.shutdown();
        Thread.sleep(100);

        // All requests should have either completed or been rejected
        int total = completed.get() + rejected.get();
        assertEquals(50, total, "All 50 requests should be accounted for");
        assertTrue(completed.get() > 0, "Some requests should have completed");
        // Note: rejected might be 0 if drain started after all acquired
    }

    @Test
    @Timeout(10)
    void routerSwapDuringTraffic() throws Exception {
        // Register v1
        KubeFnHandler v1Handler = req -> KubeFnResponse.ok(Map.of("version", "v1"));
        router.register(new String[]{"GET"}, "/api/test",
                new FunctionEntry("grp", "Fn", "com.Fn", "rev-v1", v1Handler));

        // Verify v1 is serving
        var resolved = router.resolve("GET", "/api/test");
        assertTrue(resolved.isPresent());
        assertEquals("rev-v1", resolved.get().entry().revisionId());

        // Swap to v2 (simulates hot-swap)
        router.unregisterGroup("grp");
        KubeFnHandler v2Handler = req -> KubeFnResponse.ok(Map.of("version", "v2"));
        router.register(new String[]{"GET"}, "/api/test",
                new FunctionEntry("grp", "Fn", "com.Fn", "rev-v2", v2Handler));

        // Verify v2 is serving
        resolved = router.resolve("GET", "/api/test");
        assertTrue(resolved.isPresent());
        assertEquals("rev-v2", resolved.get().entry().revisionId());

        // Execute v2 handler
        var response = resolved.get().entry().handler().handle(null);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.body();
        assertEquals("v2", body.get("version"));
    }

    @Test
    @Timeout(5)
    void drainTimeoutForcesUnload() {
        // Simulate a stuck request that never completes
        drainManager.acquireRequest("stuck-group");

        // Drain with short timeout
        boolean drained = drainManager.drainAndWait("stuck-group", 500);
        assertFalse(drained); // Should timeout, not hang forever
    }

    @Test
    @Timeout(10)
    void revisionManagerWeightedSwap() {
        RevisionManager revisionManager = new RevisionManager();
        revisionManager.registerRevision("grp", "rev-v1", RevisionState.ACTIVE);

        // All traffic to v1
        for (int i = 0; i < 10; i++) {
            assertEquals("rev-v1", revisionManager.selectRevision("grp"));
        }

        // Deploy v2 with canary (10%)
        revisionManager.registerRevision("grp", "rev-v2", RevisionState.ACTIVE);
        revisionManager.setWeight("rev-v1", 90);
        revisionManager.setWeight("rev-v2", 10);

        // Run 1000 selections
        int v1Count = 0, v2Count = 0;
        for (int i = 0; i < 1000; i++) {
            String selected = revisionManager.selectRevision("grp");
            if ("rev-v1".equals(selected)) v1Count++;
            if ("rev-v2".equals(selected)) v2Count++;
        }

        // v1 should get ~90%, v2 ~10% (with some variance)
        assertTrue(v1Count > 700, "v1 should get majority: " + v1Count);
        assertTrue(v2Count > 30, "v2 should get some traffic: " + v2Count);
    }
}

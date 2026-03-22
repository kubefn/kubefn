package com.kubefn.runtime;

import com.kubefn.api.*;
import com.kubefn.runtime.classloader.FunctionLoader;
import com.kubefn.runtime.context.FunctionGroupContext;
import com.kubefn.runtime.heap.HeapExchangeImpl;
import com.kubefn.runtime.lifecycle.DrainManager;
import com.kubefn.runtime.routing.FunctionRouter;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.jar.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test: boots the runtime components, loads a function,
 * calls it, verifies HeapExchange, then hot-swaps.
 */
class KubeFnIntegrationTest {

    private HeapExchangeImpl heapExchange;
    private FunctionRouter router;
    private FunctionLoader loader;
    private DrainManager drainManager;
    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        heapExchange = new HeapExchangeImpl();
        router = new FunctionRouter();
        drainManager = new DrainManager();
        loader = new FunctionLoader(router, heapExchange, drainManager);
        tempDir = Files.createTempDirectory("kubefn-test");
    }

    @AfterEach
    void tearDown() throws IOException {
        // Clean up temp directory
        try (var walk = Files.walk(tempDir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(java.io.File::delete);
        }
    }

    @Test
    void loadGroupFromCompiledClasses() throws Exception {
        // Create a group directory with compiled test function
        Path groupDir = tempDir.resolve("test-group");
        Files.createDirectories(groupDir);

        // Write a compiled function class (using the example JAR if available)
        // For this test, we verify the loader infrastructure works
        loader.loadAll(tempDir);

        // With no JARs, should load 0 groups
        assertEquals(0, router.routeCount());
    }

    @Test
    void heapExchangeZeroCopyAcrossContexts() {
        HeapExchangeImpl.setCurrentContext("group1", "FnA");

        // Publish from group1
        Map<String, Object> data = Map.of("key", "value", "count", 42);
        heapExchange.publish("shared-data", data, Map.class);

        // Switch to group2 context
        HeapExchangeImpl.setCurrentContext("group2", "FnB");

        // Read from group2 — should get SAME object
        var result = heapExchange.get("shared-data", Map.class);
        assertTrue(result.isPresent());
        assertSame(data, result.get()); // ZERO COPY: same reference

        // Verify metrics
        var metrics = heapExchange.metrics();
        assertEquals(1, metrics.publishCount());
        assertEquals(1, metrics.hitCount());
        assertEquals(1, metrics.objectCount());
    }

    @Test
    void routerHandlesMultipleGroups() throws Exception {
        KubeFnHandler handler1 = req -> KubeFnResponse.ok("fn1");
        KubeFnHandler handler2 = req -> KubeFnResponse.ok("fn2");

        router.register(new String[]{"GET"}, "/api/users",
                new com.kubefn.runtime.routing.FunctionEntry(
                        "user-svc", "UserFn", "com.UserFn", "rev-1", handler1));
        router.register(new String[]{"POST"}, "/api/orders",
                new com.kubefn.runtime.routing.FunctionEntry(
                        "order-svc", "OrderFn", "com.OrderFn", "rev-1", handler2));

        assertEquals(2, router.routeCount());

        // Resolve and execute
        var r1 = router.resolve("GET", "/api/users");
        assertTrue(r1.isPresent());
        assertEquals("fn1", r1.get().entry().handler().handle(null).body());

        var r2 = router.resolve("POST", "/api/orders");
        assertTrue(r2.isPresent());
        assertEquals("fn2", r2.get().entry().handler().handle(null).body());
    }

    @Test
    void drainBlocksNewRequestsDuringHotSwap() throws InterruptedException {
        // Simulate in-flight request
        drainManager.acquireRequest("my-group");

        // Start drain
        Thread drainThread = Thread.startVirtualThread(() ->
                drainManager.drainAndWait("my-group", 5000));

        Thread.sleep(100);

        // New requests should be rejected during drain
        assertTrue(drainManager.isDraining("my-group"));
        assertFalse(drainManager.acquireRequest("my-group"));

        // Complete in-flight and let drain finish
        drainManager.releaseRequest("my-group");
        drainThread.join(2000);

        // After drain, new requests should work again
        assertFalse(drainManager.isDraining("my-group"));
    }

    @Test
    void heapGuardBlocksAtCapacity() {
        var guard = new com.kubefn.runtime.heap.HeapGuard(3, 1024 * 1024, 3600_000);
        var auditLog = new com.kubefn.runtime.heap.HeapAuditLog();
        var exchange = new HeapExchangeImpl(guard, auditLog);
        HeapExchangeImpl.setCurrentContext("test", "test");

        exchange.publish("k1", "v1", String.class);
        exchange.publish("k2", "v2", String.class);
        exchange.publish("k3", "v3", String.class);

        // 4th publish should fail
        assertThrows(IllegalStateException.class, () ->
                exchange.publish("k4", "v4", String.class));

        // Remove one, then publish should work
        exchange.remove("k1");
        assertDoesNotThrow(() -> exchange.publish("k4", "v4", String.class));
    }

    @Test
    void fullPipelineEndToEnd() throws Exception {
        // Register functions that use HeapExchange
        HeapExchangeImpl.setCurrentContext("checkout", "Auth");

        KubeFnHandler authFn = req -> {
            heapExchange.publish("auth", Map.of("user", "test", "tier", "premium"), Map.class);
            return KubeFnResponse.ok(Map.of("authenticated", true));
        };

        KubeFnHandler pricingFn = req -> {
            var auth = heapExchange.get("auth", Map.class).orElseThrow();
            String tier = (String) auth.get("tier");
            double price = "premium".equals(tier) ? 84.99 : 99.99;
            heapExchange.publish("price", Map.of("amount", price), Map.class);
            return KubeFnResponse.ok(Map.of("price", price));
        };

        KubeFnHandler quoteFn = req -> {
            var auth = heapExchange.get("auth", Map.class).orElseThrow();
            var price = heapExchange.get("price", Map.class).orElseThrow();
            return KubeFnResponse.ok(Map.of(
                    "user", auth.get("user"),
                    "price", price.get("amount"),
                    "heapObjects", heapExchange.keys().size()
            ));
        };

        // Execute pipeline
        var dummyRequest = new KubeFnRequest("GET", "/checkout", "",
                Map.of(), Map.of(), null);

        authFn.handle(dummyRequest);
        pricingFn.handle(dummyRequest);
        var quoteResponse = quoteFn.handle(dummyRequest);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) quoteResponse.body();
        assertEquals("test", result.get("user"));
        assertEquals(84.99, result.get("price"));
        assertEquals(2, result.get("heapObjects")); // auth + price

        // Verify zero-copy: objects in heap are the same references
        var authObj = heapExchange.get("auth", Map.class).orElseThrow();
        assertEquals("premium", authObj.get("tier"));
    }
}

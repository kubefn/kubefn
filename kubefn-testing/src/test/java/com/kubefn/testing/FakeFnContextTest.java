package com.kubefn.testing;

import com.kubefn.api.HeapKey;
import com.kubefn.api.KubeFnHandler;
import com.kubefn.api.KubeFnRequest;
import com.kubefn.api.KubeFnResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FakeFnContextTest {

    // ── of() factory ───────────────────────────────────────────────

    @Test
    void ofCreatesContextWithHeap() {
        var heap = FakeHeapExchange.create()
                .with(HeapKey.of("test:val", String.class), "hello");

        var ctx = FakeFnContext.of(heap);

        assertNotNull(ctx.heap());
        assertEquals("hello", ctx.heap().require(HeapKey.of("test:val", String.class)));
    }

    @Test
    void ofSetsDefaults() {
        var ctx = FakeFnContext.of(FakeHeapExchange.create());

        assertEquals("test-group", ctx.groupName());
        assertEquals("test-rev", ctx.revisionId());
        assertNotNull(ctx.requestId());
        assertNotNull(ctx.logger());
        assertNotNull(ctx.config());
        assertTrue(ctx.config().isEmpty());
    }

    // ── Builder ────────────────────────────────────────────────────

    @Test
    void builderSetsAllFields() {
        var heap = FakeHeapExchange.create();

        var ctx = FakeFnContext.builder()
                .heap(heap)
                .groupName("my-group")
                .revisionId("rev-42")
                .requestId("req-abc")
                .config("env", "test")
                .build();

        assertSame(heap, ctx.heap());
        assertEquals("my-group", ctx.groupName());
        assertEquals("rev-42", ctx.revisionId());
        assertEquals("req-abc", ctx.requestId());
        assertEquals("test", ctx.config().get("env"));
    }

    // ── registerFunction by instance ───────────────────────────────

    @Test
    void registerFunctionByInstance() {
        var stub = new StubPricingFunction();

        var ctx = FakeFnContext.builder()
                .registerFunction(stub)
                .build();

        StubPricingFunction resolved = ctx.getFunction(StubPricingFunction.class);
        assertSame(stub, resolved);
    }

    // ── registerFunction by class + instance ───────────────────────

    @Test
    void registerFunctionByClassAndInstance() {
        var stub = new StubPricingFunction();

        var ctx = FakeFnContext.builder()
                .registerFunction(StubPricingFunction.class, stub)
                .build();

        StubPricingFunction resolved = ctx.getFunction(StubPricingFunction.class);
        assertSame(stub, resolved);
    }

    @Test
    void registerMultipleFunctions() {
        var pricing = new StubPricingFunction();
        var tax = new StubTaxFunction();

        var ctx = FakeFnContext.builder()
                .registerFunction(StubPricingFunction.class, pricing)
                .registerFunction(StubTaxFunction.class, tax)
                .build();

        assertSame(pricing, ctx.getFunction(StubPricingFunction.class));
        assertSame(tax, ctx.getFunction(StubTaxFunction.class));
    }

    // ── getFunction throws for unregistered ────────────────────────

    @Test
    void getFunctionThrowsForUnregistered() {
        var ctx = FakeFnContext.of(FakeHeapExchange.create());

        var ex = assertThrows(IllegalStateException.class,
                () -> ctx.getFunction(StubPricingFunction.class));

        assertTrue(ex.getMessage().contains("StubPricingFunction"),
                "Error should name the missing function class");
    }

    // ── Pipeline test scenario ─────────────────────────────────────

    @Test
    void pipelineTestScenario() {
        // Set up heap with initial data
        var heap = FakeHeapExchange.create()
                .with(HeapKey.of("pricing:current", String.class), "100.00");

        // Create stubs that write to heap
        var taxFn = new StubTaxFunction();

        var ctx = FakeFnContext.builder()
                .heap(heap)
                .groupName("checkout")
                .registerFunction(StubTaxFunction.class, taxFn)
                .build();

        // Simulate orchestrator calling the tax function
        StubTaxFunction resolved = ctx.getFunction(StubTaxFunction.class);
        assertNotNull(resolved);

        // Verify heap has initial data
        assertEquals("100.00", ctx.heap().require(HeapKey.of("pricing:current", String.class)));
    }

    // ── Stubs ──────────────────────────────────────────────────────

    static class StubPricingFunction implements KubeFnHandler {
        @Override
        public KubeFnResponse handle(KubeFnRequest request) {
            return KubeFnResponse.ok(java.util.Map.of("price", 99.99));
        }
    }

    static class StubTaxFunction implements KubeFnHandler {
        @Override
        public KubeFnResponse handle(KubeFnRequest request) {
            return KubeFnResponse.ok(java.util.Map.of("tax", 8.25));
        }
    }
}

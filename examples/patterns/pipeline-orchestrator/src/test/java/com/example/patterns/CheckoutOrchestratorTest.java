package com.example.patterns;

import com.kubefn.api.KubeFnResponse;
import com.kubefn.contracts.HeapKeys;
import com.kubefn.testing.FakeHeapExchange;
import com.kubefn.testing.FakeFnContext;
import com.kubefn.testing.TestRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CheckoutOrchestratorTest {

    @Test
    void executesFullPipeline() throws Exception {
        var heap = FakeHeapExchange.create();

        // Register all step functions so getFunction() works
        var auth = new AuthStep();
        var pricing = new PricingStep();
        var tax = new TaxStep();
        var fraud = new FraudStep();

        var ctx = FakeFnContext.builder()
                .heap(heap)
                .registerFunction(auth)
                .registerFunction(pricing)
                .registerFunction(tax)
                .registerFunction(fraud)
                .build();

        // Wire context into all functions
        auth.setContext(ctx);
        pricing.setContext(ctx);
        tax.setContext(ctx);
        fraud.setContext(ctx);

        var orchestrator = new CheckoutOrchestrator();
        orchestrator.setContext(ctx);

        KubeFnResponse response = orchestrator.handle(TestRequest.post("{}", Map.of()));

        assertEquals(200, response.statusCode());

        // Verify all steps published to heap
        assertTrue(heap.keys().stream().anyMatch(k -> k.startsWith("auth:")), "Auth should publish");
        assertTrue(heap.contains(HeapKeys.PRICING_CURRENT), "Pricing should publish");
        assertTrue(heap.contains(HeapKeys.TAX_CALCULATED), "Tax should publish");
        assertTrue(heap.contains(HeapKeys.FRAUD_RESULT), "Fraud should publish");
    }

    @Test
    void publishCountReflectsAllSteps() throws Exception {
        var heap = FakeHeapExchange.create();

        var auth = new AuthStep();
        var pricing = new PricingStep();
        var tax = new TaxStep();
        var fraud = new FraudStep();

        var ctx = FakeFnContext.builder()
                .heap(heap)
                .registerFunction(auth)
                .registerFunction(pricing)
                .registerFunction(tax)
                .registerFunction(fraud)
                .build();

        auth.setContext(ctx);
        pricing.setContext(ctx);
        tax.setContext(ctx);
        fraud.setContext(ctx);

        var orchestrator = new CheckoutOrchestrator();
        orchestrator.setContext(ctx);
        orchestrator.handle(TestRequest.post("{}", Map.of()));

        assertTrue(heap.publishCount() >= 4, "At least 4 publishes (auth, pricing, tax, fraud)");
    }
}

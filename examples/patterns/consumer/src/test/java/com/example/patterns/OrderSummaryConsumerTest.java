package com.example.patterns;

import com.kubefn.api.KubeFnResponse;
import com.kubefn.contracts.*;
import com.kubefn.testing.FakeHeapExchange;
import com.kubefn.testing.FakeFnContext;
import com.kubefn.testing.TestRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrderSummaryConsumerTest {

    @Test
    void assemblesOrderFromHeap() throws Exception {
        var heap = FakeHeapExchange.create()
                .with(HeapKeys.PRICING_CURRENT, new PricingResult("USD", 100, 0.1, 90))
                .with(HeapKeys.auth("user-1"), new AuthContext("user-1", true, "premium", List.of("admin"), List.of("read"), System.currentTimeMillis() + 3600000, "sess-1"))
                .with(HeapKeys.TAX_CALCULATED, new TaxCalculation(90, 0.0825, 7.43, 97.43));

        var ctx = FakeFnContext.of(heap);
        var fn = new OrderSummaryConsumer();
        fn.setContext(ctx);

        KubeFnResponse response = fn.handle(TestRequest.get("userId", "user-1"));

        assertEquals(200, response.statusCode());
        assertNotNull(response.body());
    }

    @Test
    void handlesOptionalShippingGracefully() throws Exception {
        // No shipping estimate on heap — should degrade gracefully, not throw
        var heap = FakeHeapExchange.create()
                .with(HeapKeys.PRICING_CURRENT, new PricingResult("USD", 100, 0.1, 90))
                .with(HeapKeys.auth("user-1"), new AuthContext("user-1", true, "premium", List.of(), List.of(), 0, "s1"))
                .with(HeapKeys.TAX_CALCULATED, new TaxCalculation(90, 0.0825, 7.43, 97.43));

        var ctx = FakeFnContext.of(heap);
        var fn = new OrderSummaryConsumer();
        fn.setContext(ctx);

        // Should not throw even without shipping
        KubeFnResponse response = fn.handle(TestRequest.get("userId", "user-1"));
        assertEquals(200, response.statusCode());
    }
}

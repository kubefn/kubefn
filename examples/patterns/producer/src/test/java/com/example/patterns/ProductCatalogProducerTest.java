package com.example.patterns;

import com.kubefn.api.KubeFnResponse;
import com.kubefn.contracts.HeapKeys;
import com.kubefn.contracts.PricingResult;
import com.kubefn.contracts.InventoryStatus;
import com.kubefn.testing.FakeHeapExchange;
import com.kubefn.testing.FakeFnContext;
import com.kubefn.testing.TestRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProductCatalogProducerTest {

    @Test
    void publishesPricingToHeap() throws Exception {
        var heap = FakeHeapExchange.create();
        var ctx = FakeFnContext.of(heap);
        var fn = new ProductCatalogProducer();
        fn.setContext(ctx);

        KubeFnResponse response = fn.handle(TestRequest.empty());

        assertEquals(200, response.statusCode());
        assertTrue(heap.contains(HeapKeys.PRICING_CURRENT));
    }

    @Test
    void publishesInventoryToHeap() throws Exception {
        var heap = FakeHeapExchange.create();
        var ctx = FakeFnContext.of(heap);
        var fn = new ProductCatalogProducer();
        fn.setContext(ctx);

        fn.handle(TestRequest.empty());

        // Should publish at least one inventory key
        assertTrue(heap.keys().stream().anyMatch(k -> k.startsWith("inventory:")));
    }

    @Test
    void publishCountMatchesExpected() throws Exception {
        var heap = FakeHeapExchange.create();
        var ctx = FakeFnContext.of(heap);
        var fn = new ProductCatalogProducer();
        fn.setContext(ctx);

        fn.handle(TestRequest.empty());

        // At least pricing + one inventory
        assertTrue(heap.publishCount() >= 2, "Should publish pricing + inventory");
    }
}

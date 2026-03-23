package com.example.patterns;

import com.kubefn.api.FnContextAware;
import com.kubefn.api.FnContext;
import com.kubefn.api.KubeFnHandler;
import com.kubefn.api.KubeFnRequest;
import com.kubefn.api.KubeFnResponse;
import com.kubefn.api.FnRoute;
import com.kubefn.api.FnGroup;
import com.kubefn.api.HeapExchange;

import com.kubefn.contracts.PricingResult;
import com.kubefn.contracts.InventoryStatus;
import com.kubefn.contracts.HeapKeys;


/**
 * PATTERN 1: PRODUCER
 * ====================
 * A Producer function creates typed data and publishes it to HeapExchange
 * so that sibling functions can read it — with ZERO serialization cost.
 *
 * KEY CONCEPT: HeapExchange is a shared in-process object store.
 * When you publish an object, siblings receive the SAME Java reference.
 * There is no JSON encoding/decoding, no HTTP call, no copying.
 * This is what makes KubeFn different from microservices.
 *
 * WHEN TO USE THIS PATTERN:
 * - Your function fetches or computes data that other functions need
 * - You want typed, compile-time-safe data sharing between functions
 * - You want to decouple data production from consumption
 *
 * WHAT THIS EXAMPLE DEMONSTRATES:
 * 1. Creating instances of contract types (PricingResult, InventoryStatus)
 * 2. Publishing objects with HeapKeys constants for discoverable keys
 * 3. Publishing multiple objects in a single function invocation
 * 4. Returning a summary response to the caller
 */
@FnRoute(path = "/catalog/load", methods = {"POST"})
@FnGroup("patterns")
public class ProductCatalogProducer implements KubeFnHandler, FnContextAware {

    // FnContext is injected by the runtime BEFORE handle() is called.
    // It provides access to HeapExchange, logging, sibling functions, and metadata.
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) {
        // The runtime calls this once during initialization.
        // Store the context — you will need it in handle().
        this.ctx = context;
    }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        // Step 1: Get a reference to HeapExchange.
        // HeapExchange is the shared memory space for THIS function group.
        // All functions in @FnGroup("patterns") share the same heap instance.
        HeapExchange heap = ctx.heap();

        ctx.logger().info("ProductCatalogProducer: loading catalog data into heap");

        // Step 2: Extract input from the request.
        // queryParam() returns Optional<String> — always handle the empty case.
        String sku = request.queryParam("sku").orElse("DEFAULT-SKU-001");
        String currency = request.queryParam("currency").orElse("USD");

        // Step 3: Create a PricingResult contract object.
        // PricingResult is a record defined in kubefn-contracts.
        // Using records ensures immutability — once published, data cannot be
        // accidentally mutated by a consumer (records have no setters).
        PricingResult pricing = new PricingResult(
            currency,       // currency: "USD"
            99.99,          // basePrice: the pre-discount price
            10.0,           // discount: percentage off
            89.99           // finalPrice: what the customer actually pays
        );

        // Step 4: Publish pricing to HeapExchange.
        // HeapKeys.PRICING_CURRENT is a well-known constant string.
        // Using HeapKeys constants instead of raw strings prevents typos
        // and makes it easy to find all producers/consumers of a given key.
        //
        // The third argument is the type — this enables typed retrieval:
        //   heap.get(HeapKeys.PRICING_CURRENT, PricingResult.class)
        //
        // IMPORTANT: publish() returns a HeapCapsule, which wraps the object
        // with metadata (timestamp, producer ID, etc.). You rarely need it,
        // but it is available for audit/debugging.
        heap.publish(HeapKeys.PRICING_CURRENT, pricing, PricingResult.class);

        ctx.logger().info("Published PricingResult: finalPrice={}", pricing.finalPrice());

        // Step 5: Create and publish an InventoryStatus contract object.
        // This demonstrates publishing MULTIPLE objects — a producer can
        // publish as many heap entries as needed.
        InventoryStatus inventory = new InventoryStatus(
            sku,            // sku: the product identifier
            150,            // available: units in stock
            12,             // reserved: units held for pending orders
            "warehouse-us-east-1"  // warehouse: where the stock lives
        );

        // HeapKeys.inventory(sku) generates a dynamic key: "inventory:<sku>"
        // Dynamic keys are used when you have multiple instances of the same
        // type (e.g., inventory for different SKUs). Static keys like
        // PRICING_CURRENT are used for singleton data.
        heap.publish(HeapKeys.inventory(sku), inventory, InventoryStatus.class);

        ctx.logger().info("Published InventoryStatus: sku={}, available={}", sku, inventory.available());

        // Step 6: Return a summary response.
        // The response goes back to the HTTP caller. The heap data goes to siblings.
        // These are two separate channels — the response is for the external client,
        // while the heap is for internal function-to-function communication.
        return KubeFnResponse.ok(
            "{\"status\": \"catalog_loaded\","
            + "\"sku\": \"" + sku + "\","
            + "\"pricingPublished\": true,"
            + "\"inventoryPublished\": true,"
            + "\"heapKeys\": [\"" + HeapKeys.PRICING_CURRENT + "\", \"" + HeapKeys.inventory(sku) + "\"]}"
        );
    }
}

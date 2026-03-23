package com.example.patterns;

import com.kubefn.api.FnContextAware;
import com.kubefn.api.FnContext;
import com.kubefn.api.KubeFnHandler;
import com.kubefn.api.KubeFnRequest;
import com.kubefn.api.KubeFnResponse;
import com.kubefn.api.FnRoute;
import com.kubefn.api.FnGroup;
import com.kubefn.api.HeapExchange;

import com.kubefn.contracts.AuthContext;
import com.kubefn.contracts.PricingResult;
import com.kubefn.contracts.TaxCalculation;
import com.kubefn.contracts.FraudScore;
import com.kubefn.contracts.HeapKeys;


/**
 * PATTERN 4: PIPELINE ORCHESTRATOR
 * ==================================
 * The orchestrator calls sibling functions in sequence via ctx.getFunction(),
 * each sibling publishes its result to HeapExchange, and the orchestrator
 * reads ALL results from heap to assemble the final response.
 *
 * KEY CONCEPT: Function composition via shared heap.
 * Unlike microservices where an orchestrator must parse HTTP responses from
 * each service, a KubeFn orchestrator does not need to handle any responses
 * from the steps. Each step publishes directly to heap, and the orchestrator
 * reads from heap. The step responses are just status confirmations.
 *
 * THIS IS THE CANONICAL "CHECKOUT PIPELINE" PATTERN:
 *
 *   Request --> CheckoutOrchestrator
 *                |
 *                +--> AuthStep        --publishes--> AuthContext
 *                +--> PricingStep     --publishes--> PricingResult
 *                +--> TaxStep         --reads PricingResult, publishes--> TaxCalculation
 *                +--> FraudStep       --reads AuthContext + PricingResult, publishes--> FraudScore
 *                |
 *                +-- reads ALL from heap --> assembles final response
 *
 * EXECUTION ORDER MATTERS:
 *   1. AuthStep      (independent — can run first)
 *   2. PricingStep   (independent — can run in parallel with AuthStep)
 *   3. TaxStep       (depends on PricingStep)
 *   4. FraudStep     (depends on AuthStep AND PricingStep)
 *
 * WHY NOT just call each step and use its HTTP response?
 * Because the heap approach gives you:
 * 1. Type safety — you read PricingResult.class, not a JSON string
 * 2. Zero copy — same object reference, no serialization
 * 3. Decoupling — steps don't know about the orchestrator's response format
 * 4. Composability — any new step can read any existing heap data
 *
 * WHAT THIS EXAMPLE DEMONSTRATES:
 * 1. Calling sibling functions via ctx.getFunction(Class).handle(request)
 * 2. Reading all results from heap after pipeline execution
 * 3. Timing each step for performance monitoring
 * 4. Building _meta with pipeline execution statistics
 * 5. Error handling when a step fails
 */
@FnRoute(path = "/checkout/full", methods = {"POST"})
@FnGroup("patterns")
public class CheckoutOrchestrator implements KubeFnHandler, FnContextAware {

    private FnContext ctx;

    @Override
    public void setContext(FnContext context) {
        this.ctx = context;
    }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        HeapExchange heap = ctx.heap();
        long pipelineStart = System.nanoTime();

        ctx.logger().info("CheckoutOrchestrator: starting checkout pipeline");

        String userId = request.queryParam("userId").orElse("user-001");

        // ---------------------------------------------------------------
        // STEP 1: Authentication
        // ---------------------------------------------------------------
        // Call AuthStep via ctx.getFunction(). This is an in-process call —
        // there is NO HTTP request. The function runs in the same JVM,
        // same thread, with access to the same HeapExchange.
        //
        // getFunction() returns a KubeFnHandler instance managed by the runtime.
        // The runtime has already called setContext() on it, so it has access
        // to the same heap, logger, etc.
        long stepStart = System.nanoTime();
        KubeFnResponse authResponse = ctx.getFunction(AuthStep.class).handle(request);
        long authTimeNs = System.nanoTime() - stepStart;

        ctx.logger().info("Step 1 (auth) completed in {}ms", authTimeNs / 1_000_000);

        // We do NOT parse authResponse. AuthStep already published AuthContext
        // to heap. The response is just a status confirmation for logging.

        // ---------------------------------------------------------------
        // STEP 2: Pricing
        // ---------------------------------------------------------------
        // PricingStep is INDEPENDENT of AuthStep — it does not read AuthContext.
        // In a more advanced version, you could run Steps 1 and 2 in parallel
        // using CompletableFuture, since they have no data dependency.
        stepStart = System.nanoTime();
        KubeFnResponse pricingResponse = ctx.getFunction(PricingStep.class).handle(request);
        long pricingTimeNs = System.nanoTime() - stepStart;

        ctx.logger().info("Step 2 (pricing) completed in {}ms", pricingTimeNs / 1_000_000);

        // ---------------------------------------------------------------
        // STEP 3: Tax Calculation
        // ---------------------------------------------------------------
        // TaxStep DEPENDS on PricingStep — it reads PricingResult from heap.
        // This is why we call it AFTER PricingStep. If we called it before,
        // TaxStep would throw because PricingResult would not be in heap yet.
        stepStart = System.nanoTime();
        KubeFnResponse taxResponse = ctx.getFunction(TaxStep.class).handle(request);
        long taxTimeNs = System.nanoTime() - stepStart;

        ctx.logger().info("Step 3 (tax) completed in {}ms", taxTimeNs / 1_000_000);

        // ---------------------------------------------------------------
        // STEP 4: Fraud Scoring
        // ---------------------------------------------------------------
        // FraudStep DEPENDS on BOTH AuthStep and PricingStep — it reads
        // AuthContext and PricingResult from heap. Both must be present.
        stepStart = System.nanoTime();
        KubeFnResponse fraudResponse = ctx.getFunction(FraudStep.class).handle(request);
        long fraudTimeNs = System.nanoTime() - stepStart;

        ctx.logger().info("Step 4 (fraud) completed in {}ms", fraudTimeNs / 1_000_000);

        // ---------------------------------------------------------------
        // READ ALL RESULTS FROM HEAP
        // ---------------------------------------------------------------
        // Now that all steps have published their data to heap, we read
        // everything in one place. This is the "fan-in" part of the pipeline.
        //
        // ZERO COPY: Each .get() returns the same Java object reference that
        // the step published. No serialization, no copying, no parsing.

        AuthContext auth = heap.get(HeapKeys.auth(userId), AuthContext.class)
            .orElseThrow(() -> new IllegalStateException("AuthContext missing after pipeline execution"));

        PricingResult pricing = heap.get(HeapKeys.PRICING_CURRENT, PricingResult.class)
            .orElseThrow(() -> new IllegalStateException("PricingResult missing after pipeline execution"));

        TaxCalculation tax = heap.get(HeapKeys.TAX_CALCULATED, TaxCalculation.class)
            .orElseThrow(() -> new IllegalStateException("TaxCalculation missing after pipeline execution"));

        FraudScore fraud = heap.get(HeapKeys.FRAUD_RESULT, FraudScore.class)
            .orElseThrow(() -> new IllegalStateException("FraudScore missing after pipeline execution"));

        // ---------------------------------------------------------------
        // ASSEMBLE FINAL RESPONSE WITH _meta
        // ---------------------------------------------------------------
        long totalTimeNs = System.nanoTime() - pipelineStart;

        // The _meta section provides pipeline execution statistics.
        // This is invaluable for:
        // 1. Performance monitoring — which step is the bottleneck?
        // 2. Debugging — did all steps execute?
        // 3. SLA tracking — is the pipeline within latency budget?
        // 4. Capacity planning — how does latency change under load?

        ctx.logger().info("CheckoutOrchestrator: pipeline completed in {}ms, fraud.approved={}",
            totalTimeNs / 1_000_000, fraud.approved());

        return KubeFnResponse.ok(
            "{\"checkout\": {"
            + "  \"userId\": \"" + auth.userId() + "\","
            + "  \"authenticated\": " + auth.authenticated() + ","
            + "  \"tier\": \"" + auth.tier() + "\","
            + "  \"pricing\": {"
            + "    \"currency\": \"" + pricing.currency() + "\","
            + "    \"basePrice\": " + pricing.basePrice() + ","
            + "    \"discount\": " + pricing.discount() + ","
            + "    \"finalPrice\": " + pricing.finalPrice()
            + "  },"
            + "  \"tax\": {"
            + "    \"subtotal\": " + tax.subtotal() + ","
            + "    \"taxRate\": " + tax.taxRate() + ","
            + "    \"taxAmount\": " + tax.taxAmount() + ","
            + "    \"total\": " + tax.total()
            + "  },"
            + "  \"fraud\": {"
            + "    \"riskScore\": " + fraud.riskScore() + ","
            + "    \"approved\": " + fraud.approved() + ","
            + "    \"reason\": \"" + fraud.reason() + "\","
            + "    \"model\": \"" + fraud.model() + "\""
            + "  },"
            + "  \"approved\": " + fraud.approved()
            + "},"
            + "\"_meta\": {"
            + "  \"pipeline\": \"checkout\","
            + "  \"steps\": 4,"
            + "  \"timings\": {"
            + "    \"authMs\": " + (authTimeNs / 1_000_000) + ","
            + "    \"pricingMs\": " + (pricingTimeNs / 1_000_000) + ","
            + "    \"taxMs\": " + (taxTimeNs / 1_000_000) + ","
            + "    \"fraudMs\": " + (fraudTimeNs / 1_000_000) + ","
            + "    \"totalMs\": " + (totalTimeNs / 1_000_000)
            + "  },"
            + "  \"heapKeysUsed\": ["
            + "    \"" + HeapKeys.auth(userId) + "\","
            + "    \"" + HeapKeys.PRICING_CURRENT + "\","
            + "    \"" + HeapKeys.TAX_CALCULATED + "\","
            + "    \"" + HeapKeys.FRAUD_RESULT + "\""
            + "  ],"
            + "  \"revisionId\": \"" + ctx.revisionId() + "\","
            + "  \"group\": \"" + ctx.groupName() + "\""
            + "}}"
        );
    }
}

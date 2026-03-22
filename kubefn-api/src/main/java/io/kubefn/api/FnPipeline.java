package io.kubefn.api;

import java.util.List;

/**
 * A composable in-memory execution graph. Functions chain together
 * at heap speed — no HTTP, no serialization, no broker.
 *
 * <p>The runtime owns the graph and can optimize it: fuse hot paths,
 * elide intermediate allocations, memoize subgraphs.
 *
 * <h3>Example:</h3>
 * <pre>{@code
 * KubeFnResponse response = ctx.pipeline()
 *     .step(AuthFunction.class)
 *     .step(InventoryFunction.class)
 *     .parallel(PricingFunction.class, ShippingFunction.class)
 *     .step(FraudCheckFunction.class)
 *     .build()
 *     .execute(request);
 * // 5 functions, ~50 microseconds. Not 5 HTTP calls at ~5ms each.
 * }</pre>
 */
public interface FnPipeline {

    /**
     * Add a sequential step to the pipeline.
     * The output of the previous step becomes the input to this one.
     */
    FnPipeline step(Class<? extends KubeFnHandler> handlerClass);

    /**
     * Add parallel steps. All execute concurrently on virtual threads.
     * Their responses are collected and merged.
     */
    @SuppressWarnings("unchecked")
    FnPipeline parallel(Class<? extends KubeFnHandler>... handlerClasses);

    /**
     * Build the pipeline into an executable graph.
     */
    ExecutablePipeline build();

    /**
     * An executable, optimizable pipeline ready to process requests.
     */
    interface ExecutablePipeline {

        /**
         * Execute the pipeline against a request.
         * All steps run in-memory at heap speed.
         */
        KubeFnResponse execute(KubeFnRequest request);

        /**
         * Get the ordered list of steps for introspection/tracing.
         */
        List<String> steps();
    }
}

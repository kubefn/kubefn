package com.kubefn.shared;

import com.kubefn.api.*;

import java.util.*;

/**
 * Simplified pipeline builder for common orchestration patterns.
 *
 * <pre>{@code
 * var result = PipelineBuilder.create(ctx, request)
 *     .step("auth", AuthFunction.class)
 *     .step("pricing", PricingFunction.class)
 *     .step("tax", TaxFunction.class)
 *     .execute();
 *
 * // result contains timing, step results, and heap state
 * return KubeFnResponse.ok(result);
 * }</pre>
 */
public final class PipelineBuilder {

    private final FnContext ctx;
    private final KubeFnRequest request;
    private final List<StepDef> steps = new ArrayList<>();

    private PipelineBuilder(FnContext ctx, KubeFnRequest request) {
        this.ctx = ctx;
        this.request = request;
    }

    public static PipelineBuilder create(FnContext ctx, KubeFnRequest request) {
        return new PipelineBuilder(ctx, request);
    }

    public PipelineBuilder step(String name, Class<? extends KubeFnHandler> handlerClass) {
        steps.add(new StepDef(name, handlerClass));
        return this;
    }

    /**
     * Execute all steps sequentially with timing.
     */
    public Map<String, Object> execute() throws Exception {
        long startNanos = System.nanoTime();
        var stepResults = new ArrayList<Map<String, Object>>();
        int errors = 0;

        for (StepDef step : steps) {
            long stepStart = System.nanoTime();
            String status = "OK";
            String error = null;

            try {
                KubeFnHandler handler = ctx.getFunction(step.handlerClass);
                handler.handle(request);
            } catch (Exception e) {
                status = "ERROR";
                error = e.getMessage();
                errors++;
                ctx.logger().error("Pipeline step '{}' failed: {}", step.name, e.getMessage());
            }

            double stepMs = (System.nanoTime() - stepStart) / 1_000_000.0;
            var stepResult = new LinkedHashMap<String, Object>();
            stepResult.put("name", step.name);
            stepResult.put("durationMs", String.format("%.3f", stepMs));
            stepResult.put("status", status);
            if (error != null) stepResult.put("error", error);
            stepResults.add(stepResult);
        }

        double totalMs = (System.nanoTime() - startNanos) / 1_000_000.0;

        var result = new LinkedHashMap<String, Object>();
        result.put("_meta", Map.of(
                "pipelineSteps", steps.size(),
                "totalTimeMs", String.format("%.3f", totalMs),
                "errors", errors,
                "steps", stepResults,
                "heapObjects", ctx.heap().keys().size(),
                "zeroCopy", true
        ));

        return result;
    }

    private record StepDef(String name, Class<? extends KubeFnHandler> handlerClass) {}
}

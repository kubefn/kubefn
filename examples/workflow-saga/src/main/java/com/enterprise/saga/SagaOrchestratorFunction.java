package com.enterprise.saga;

import io.kubefn.api.*;

import java.util.*;

/**
 * Orchestrates the order saga with automatic compensation on failure.
 * Executes: create order -> reserve inventory -> capture payment -> schedule shipping.
 * If any step fails, triggers compensation for all previously completed steps.
 *
 * To trigger the compensation path, submit an order with total > $1000
 * (e.g., items=PROD-A:25:49.99 which totals $1249.75).
 */
@FnRoute(path = "/saga/execute", methods = {"POST"})
@FnGroup("order-saga")
public class SagaOrchestratorFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        long pipelineStart = System.nanoTime();
        List<Map<String, Object>> stageTiming = new ArrayList<>();
        String failedAt = null;

        // Stage 1: Create Order
        long stageStart = System.nanoTime();
        var createResponse = ctx.getFunction(OrderCreationFunction.class).handle(request);
        stageTiming.add(stageEntry("order_creation", System.nanoTime() - stageStart, createResponse.statusCode()));

        if (createResponse.statusCode() != 200) {
            failedAt = "order_creation";
            return buildSagaResult(failedAt, stageTiming, pipelineStart, createResponse);
        }

        // Stage 2: Reserve Inventory
        stageStart = System.nanoTime();
        var reserveResponse = ctx.getFunction(InventoryReservationFunction.class).handle(request);
        stageTiming.add(stageEntry("inventory_reservation", System.nanoTime() - stageStart, reserveResponse.statusCode()));

        if (reserveResponse.statusCode() != 200) {
            failedAt = "inventory_reservation";
            return triggerCompensation(request, failedAt, stageTiming, pipelineStart, reserveResponse);
        }

        // Stage 3: Capture Payment
        stageStart = System.nanoTime();
        var paymentResponse = ctx.getFunction(PaymentCaptureFunction.class).handle(request);
        stageTiming.add(stageEntry("payment_capture", System.nanoTime() - stageStart, paymentResponse.statusCode()));

        if (paymentResponse.statusCode() != 200) {
            failedAt = "payment_capture";
            return triggerCompensation(request, failedAt, stageTiming, pipelineStart, paymentResponse);
        }

        // Stage 4: Schedule Shipping
        stageStart = System.nanoTime();
        var shippingResponse = ctx.getFunction(ShippingScheduleFunction.class).handle(request);
        stageTiming.add(stageEntry("shipping_schedule", System.nanoTime() - stageStart, shippingResponse.statusCode()));

        if (shippingResponse.statusCode() != 200) {
            failedAt = "shipping_schedule";
            return triggerCompensation(request, failedAt, stageTiming, pipelineStart, shippingResponse);
        }

        // All stages completed successfully
        var order = ctx.heap().get("saga:order", Map.class).orElse(Map.of());
        var inventory = ctx.heap().get("saga:inventory", Map.class).orElse(Map.of());
        var payment = ctx.heap().get("saga:payment", Map.class).orElse(Map.of());
        var shipping = ctx.heap().get("saga:shipping", Map.class).orElse(Map.of());
        var stepLog = ctx.heap().get("saga:step-log", List.class).orElse(List.of());

        long totalNanos = System.nanoTime() - pipelineStart;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sagaStatus", "COMPLETED");
        result.put("orderId", order.get("orderId"));
        result.put("orderTotal", order.get("orderTotal"));
        result.put("transactionId", payment.get("transactionId"));
        result.put("trackingNumber", shipping.get("trackingNumber"));
        result.put("carrier", shipping.get("carrier"));
        result.put("estimatedDeliveryMs", shipping.get("estimatedDeliveryMs"));
        result.put("stepLog", stepLog);
        result.put("_meta", Map.of(
                "pipelineStages", 4,
                "stageTiming", stageTiming,
                "totalTimeMs", String.format("%.3f", totalNanos / 1_000_000.0),
                "totalTimeNanos", totalNanos,
                "heapKeysUsed", ctx.heap().keys().size(),
                "compensationTriggered", false,
                "zeroCopy", true,
                "note", "Full saga completed successfully. All 4 stages in-memory."
        ));

        return KubeFnResponse.ok(result);
    }

    @SuppressWarnings("unchecked")
    private KubeFnResponse triggerCompensation(KubeFnRequest request,
                                                String failedStep,
                                                List<Map<String, Object>> stageTiming,
                                                long pipelineStart,
                                                KubeFnResponse failureResponse) throws Exception {
        // Execute compensation
        long compStart = System.nanoTime();
        var compensationResponse = ctx.getFunction(CompensationFunction.class).handle(request);
        stageTiming.add(stageEntry("compensation", System.nanoTime() - compStart, compensationResponse.statusCode()));

        return buildSagaResult(failedStep, stageTiming, pipelineStart, failureResponse);
    }

    @SuppressWarnings("unchecked")
    private KubeFnResponse buildSagaResult(String failedStep,
                                            List<Map<String, Object>> stageTiming,
                                            long pipelineStart,
                                            KubeFnResponse failureResponse) {
        var order = ctx.heap().get("saga:order", Map.class).orElse(Map.of());
        var compensation = ctx.heap().get("saga:compensation", Map.class).orElse(null);
        var stepLog = ctx.heap().get("saga:step-log", List.class).orElse(List.of());

        long totalNanos = System.nanoTime() - pipelineStart;
        boolean compensated = compensation != null;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sagaStatus", compensated ? "COMPENSATED" : "FAILED");
        result.put("failedAt", failedStep);
        result.put("orderId", order.getOrDefault("orderId", "unknown"));
        result.put("orderTotal", order.getOrDefault("orderTotal", 0.0));
        result.put("failureDetail", failureResponse.body());
        if (compensated) {
            result.put("compensation", compensation);
        }
        result.put("stepLog", stepLog);
        result.put("_meta", Map.of(
                "pipelineStages", stageTiming.size(),
                "stageTiming", stageTiming,
                "totalTimeMs", String.format("%.3f", totalNanos / 1_000_000.0),
                "totalTimeNanos", totalNanos,
                "heapKeysUsed", ctx.heap().keys().size(),
                "compensationTriggered", compensated,
                "zeroCopy", true,
                "note", "Saga failed at " + failedStep + "." +
                        (compensated ? " Compensation executed for prior steps." : "")
        ));

        return KubeFnResponse.status(failureResponse.statusCode()).body(result);
    }

    private Map<String, Object> stageEntry(String stage, long nanos, int status) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("stage", stage);
        entry.put("durationNanos", nanos);
        entry.put("durationMs", String.format("%.3f", nanos / 1_000_000.0));
        entry.put("status", status);
        return entry;
    }
}

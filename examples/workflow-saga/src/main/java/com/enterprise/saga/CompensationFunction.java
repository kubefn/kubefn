package com.enterprise.saga;

import io.kubefn.api.*;

import java.util.*;

/**
 * Handles rollback/compensation for failed saga steps. Reverses completed steps
 * in reverse order: cancel shipping -> refund payment -> release inventory.
 * Each compensation action is logged for audit trail.
 */
@FnRoute(path = "/saga/compensate", methods = {"POST"})
@FnGroup("order-saga")
public class CompensationFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        String failedStep = request.queryParam("failedStep").orElse("payment_capture");

        var order = ctx.heap().get("saga:order", Map.class).orElse(Map.of());
        var inventory = ctx.heap().get("saga:inventory", Map.class).orElse(Map.of());
        var payment = ctx.heap().get("saga:payment", Map.class).orElse(Map.of());
        var shipping = ctx.heap().get("saga:shipping", Map.class).orElse(Map.of());

        String orderId = (String) order.getOrDefault("orderId", "unknown");
        List<Map<String, Object>> compensationActions = new ArrayList<>();

        // Determine which steps need compensation (reverse order)
        List<String> completedSteps = determineCompletedSteps(failedStep);

        for (String step : completedSteps) {
            Map<String, Object> action = switch (step) {
                case "shipping_schedule" -> compensateShipping(orderId, shipping);
                case "payment_capture" -> compensatePayment(orderId, payment);
                case "inventory_reservation" -> compensateInventory(orderId, inventory);
                case "order_creation" -> compensateOrder(orderId, order);
                default -> Map.of("step", step, "action", "UNKNOWN_STEP");
            };
            compensationActions.add(action);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", orderId);
        result.put("failedStep", failedStep);
        result.put("compensationActions", compensationActions);
        result.put("totalCompensations", compensationActions.size());
        result.put("sagaStatus", "COMPENSATED");
        result.put("timestamp", System.currentTimeMillis());

        ctx.heap().publish("saga:compensation", result, Map.class);
        return KubeFnResponse.ok(result);
    }

    /**
     * Returns completed steps in reverse order up to (but not including) the failed step.
     */
    private List<String> determineCompletedSteps(String failedStep) {
        List<String> allSteps = List.of(
                "order_creation", "inventory_reservation", "payment_capture", "shipping_schedule"
        );
        int failedIndex = allSteps.indexOf(failedStep);
        if (failedIndex <= 0) return List.of();

        List<String> toCompensate = new ArrayList<>(allSteps.subList(0, failedIndex));
        Collections.reverse(toCompensate);
        return toCompensate;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> compensateShipping(String orderId, Map<String, Object> shipping) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("step", "shipping_schedule");
        action.put("action", "CANCEL_SHIPMENT");
        action.put("trackingNumber", shipping.getOrDefault("trackingNumber", "N/A"));
        action.put("carrier", shipping.getOrDefault("carrier", "N/A"));
        action.put("refundShipping", shipping.getOrDefault("shippingCost", 0.0));
        action.put("status", "COMPENSATED");
        action.put("timestamp", System.currentTimeMillis());
        return action;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> compensatePayment(String orderId, Map<String, Object> payment) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("step", "payment_capture");
        action.put("action", "REFUND_PAYMENT");
        action.put("transactionId", payment.getOrDefault("transactionId", "N/A"));
        action.put("refundAmount", payment.getOrDefault("amount", 0.0));
        action.put("refundId", "REF-" + System.nanoTime() % 100000);
        action.put("status", "COMPENSATED");
        action.put("timestamp", System.currentTimeMillis());
        return action;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> compensateInventory(String orderId, Map<String, Object> inventory) {
        List<Map<String, Object>> reservations = (List<Map<String, Object>>)
                inventory.getOrDefault("reservations", List.of());

        List<Map<String, Object>> releasedItems = new ArrayList<>();
        for (Map<String, Object> res : reservations) {
            if ("RESERVED".equals(res.get("status"))) {
                releasedItems.add(Map.of(
                        "sku", res.getOrDefault("sku", "unknown"),
                        "reservationId", res.getOrDefault("reservationId", "unknown"),
                        "quantityReleased", res.getOrDefault("requestedQuantity", 0)
                ));
            }
        }

        Map<String, Object> action = new LinkedHashMap<>();
        action.put("step", "inventory_reservation");
        action.put("action", "RELEASE_INVENTORY");
        action.put("releasedItems", releasedItems);
        action.put("totalItemsReleased", releasedItems.size());
        action.put("status", "COMPENSATED");
        action.put("timestamp", System.currentTimeMillis());
        return action;
    }

    private Map<String, Object> compensateOrder(String orderId, Map<String, Object> order) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("step", "order_creation");
        action.put("action", "CANCEL_ORDER");
        action.put("orderId", orderId);
        action.put("previousStatus", order.getOrDefault("status", "CREATED"));
        action.put("newStatus", "CANCELLED");
        action.put("status", "COMPENSATED");
        action.put("timestamp", System.currentTimeMillis());
        return action;
    }
}

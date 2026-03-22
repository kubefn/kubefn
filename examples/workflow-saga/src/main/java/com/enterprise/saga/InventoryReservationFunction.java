package com.enterprise.saga;

import io.kubefn.api.*;

import java.util.*;

/**
 * Reserves inventory for each line item in the order. Checks stock levels
 * and creates reservation holds. Publishes reservation details to the heap
 * for potential compensation.
 */
@FnRoute(path = "/saga/reserve", methods = {"POST"})
@FnGroup("order-saga")
public class InventoryReservationFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    // Simulated warehouse inventory
    private static final Map<String, Integer> WAREHOUSE_STOCK = new HashMap<>(Map.of(
            "PROD-A", 150,
            "PROD-B", 45,
            "PROD-C", 0,    // Out of stock
            "PROD-D", 500,
            "PROD-E", 12
    ));

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var order = ctx.heap().get("saga:order", Map.class)
                .orElse(Map.of());
        String orderId = (String) order.getOrDefault("orderId", "unknown");
        List<Map<String, Object>> lineItems = (List<Map<String, Object>>)
                order.getOrDefault("lineItems", List.of());

        List<Map<String, Object>> reservations = new ArrayList<>();
        boolean allReserved = true;
        String failedSku = null;

        for (Map<String, Object> item : lineItems) {
            String sku = (String) item.get("sku");
            int requestedQty = ((Number) item.get("quantity")).intValue();
            int available = WAREHOUSE_STOCK.getOrDefault(sku, 0);

            Map<String, Object> reservation = new LinkedHashMap<>();
            reservation.put("sku", sku);
            reservation.put("requestedQuantity", requestedQty);
            reservation.put("availableStock", available);

            if (available >= requestedQty) {
                String reservationId = "RES-" + sku + "-" + System.nanoTime() % 10000;
                reservation.put("reservationId", reservationId);
                reservation.put("status", "RESERVED");
                reservation.put("warehouse", "WH-EAST-01");
                reservation.put("expiresAt", System.currentTimeMillis() + 900_000); // 15 min hold
            } else {
                reservation.put("status", "INSUFFICIENT_STOCK");
                reservation.put("shortfall", requestedQty - available);
                allReserved = false;
                failedSku = sku;
            }

            reservations.add(reservation);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", orderId);
        result.put("reservations", reservations);
        result.put("allReserved", allReserved);
        result.put("status", allReserved ? "INVENTORY_RESERVED" : "RESERVATION_FAILED");
        result.put("timestamp", System.currentTimeMillis());

        ctx.heap().publish("saga:inventory", result, Map.class);

        // Update step log
        var stepLog = ctx.heap().get("saga:step-log", List.class).orElse(new ArrayList<>());
        List<Map<String, Object>> updatedLog = new ArrayList<>(stepLog);
        updatedLog.add(Map.of(
                "step", "inventory_reservation",
                "status", allReserved ? "completed" : "failed",
                "orderId", orderId,
                "failedSku", failedSku != null ? failedSku : "none",
                "timestamp", System.currentTimeMillis()
        ));
        ctx.heap().publish("saga:step-log", updatedLog, List.class);

        if (!allReserved) {
            return KubeFnResponse.status(409).body(result);
        }

        return KubeFnResponse.ok(result);
    }
}

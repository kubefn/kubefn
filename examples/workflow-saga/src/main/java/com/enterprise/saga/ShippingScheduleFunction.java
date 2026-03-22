package com.enterprise.saga;

import com.kubefn.api.*;

import java.util.*;

/**
 * Schedules shipping for the order. Selects a carrier based on order weight,
 * destination, and delivery preference. Computes estimated delivery date.
 */
@FnRoute(path = "/saga/ship", methods = {"POST"})
@FnGroup("order-saga")
public class ShippingScheduleFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    // Carrier selection rules
    private static final List<Map<String, Object>> CARRIERS = List.of(
            Map.of("carrier", "FastShip Express", "code", "FSX",
                    "maxWeight", 50.0, "baseCost", 12.99, "transitDays", 2),
            Map.of("carrier", "Standard Logistics", "code", "STD",
                    "maxWeight", 200.0, "baseCost", 7.99, "transitDays", 5),
            Map.of("carrier", "Economy Freight", "code", "ECO",
                    "maxWeight", 1000.0, "baseCost", 4.99, "transitDays", 10)
    );

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var order = ctx.heap().get("saga:order", Map.class).orElse(Map.of());
        String orderId = (String) order.getOrDefault("orderId", "unknown");
        String address = (String) order.getOrDefault("shippingAddress", "Unknown");
        List<Map<String, Object>> lineItems = (List<Map<String, Object>>)
                order.getOrDefault("lineItems", List.of());

        String shippingPreference = request.queryParam("shipping").orElse("standard");

        // Estimate total weight (assume 0.5 kg per unit)
        double totalWeight = lineItems.stream()
                .mapToDouble(item -> ((Number) item.getOrDefault("quantity", 1)).doubleValue() * 0.5)
                .sum();

        // Select carrier based on weight and preference
        Map<String, Object> selectedCarrier = null;
        for (Map<String, Object> carrier : CARRIERS) {
            double maxWeight = ((Number) carrier.get("maxWeight")).doubleValue();
            if (totalWeight <= maxWeight) {
                if ("express".equals(shippingPreference) && "FSX".equals(carrier.get("code"))) {
                    selectedCarrier = carrier;
                    break;
                }
                if ("standard".equals(shippingPreference) && "STD".equals(carrier.get("code"))) {
                    selectedCarrier = carrier;
                    break;
                }
                if (selectedCarrier == null) {
                    selectedCarrier = carrier;
                }
            }
        }

        if (selectedCarrier == null) {
            selectedCarrier = CARRIERS.get(CARRIERS.size() - 1); // Fallback to freight
        }

        // Compute shipping cost (base + weight surcharge)
        double baseCost = ((Number) selectedCarrier.get("baseCost")).doubleValue();
        double weightSurcharge = totalWeight > 10 ? (totalWeight - 10) * 0.50 : 0;
        double shippingCost = Math.round((baseCost + weightSurcharge) * 100.0) / 100.0;

        int transitDays = ((Number) selectedCarrier.get("transitDays")).intValue();
        long estimatedDeliveryMs = System.currentTimeMillis() + (long) transitDays * 86_400_000L;

        String trackingNumber = "TRK-" + selectedCarrier.get("code") + "-" + System.nanoTime() % 100000;

        Map<String, Object> shipping = new LinkedHashMap<>();
        shipping.put("orderId", orderId);
        shipping.put("status", "SHIPPING_SCHEDULED");
        shipping.put("trackingNumber", trackingNumber);
        shipping.put("carrier", selectedCarrier.get("carrier"));
        shipping.put("carrierCode", selectedCarrier.get("code"));
        shipping.put("shippingPreference", shippingPreference);
        shipping.put("totalWeight", totalWeight);
        shipping.put("shippingCost", shippingCost);
        shipping.put("transitDays", transitDays);
        shipping.put("estimatedDeliveryMs", estimatedDeliveryMs);
        shipping.put("destinationAddress", address);
        shipping.put("timestamp", System.currentTimeMillis());

        ctx.heap().publish("saga:shipping", shipping, Map.class);

        var stepLog = ctx.heap().get("saga:step-log", List.class).orElse(new ArrayList<>());
        List<Map<String, Object>> updatedLog = new ArrayList<>(stepLog);
        updatedLog.add(Map.of(
                "step", "shipping_schedule",
                "status", "completed",
                "orderId", orderId,
                "trackingNumber", trackingNumber,
                "timestamp", System.currentTimeMillis()
        ));
        ctx.heap().publish("saga:step-log", updatedLog, List.class);

        return KubeFnResponse.ok(shipping);
    }
}

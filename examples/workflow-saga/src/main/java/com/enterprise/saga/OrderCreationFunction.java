package com.enterprise.saga;

import io.kubefn.api.*;

import java.util.*;

/**
 * Creates an order from the incoming request. Validates required fields,
 * assigns an order ID, and computes the order total from line items.
 */
@FnRoute(path = "/saga/create", methods = {"POST"})
@FnGroup("order-saga")
public class OrderCreationFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        String customerId = request.queryParam("customerId").orElse("CUST-1001");
        String itemsParam = request.queryParam("items").orElse("PROD-A:2:49.99,PROD-B:1:199.99");
        String shippingAddress = request.queryParam("address").orElse("123 Main St, Springfield, IL 62701");

        // Parse line items: format is "SKU:qty:unitPrice,SKU:qty:unitPrice"
        List<Map<String, Object>> lineItems = new ArrayList<>();
        double orderTotal = 0.0;

        for (String itemSpec : itemsParam.split(",")) {
            String[] parts = itemSpec.trim().split(":");
            if (parts.length < 3) continue;

            String sku = parts[0].trim();
            int quantity = Integer.parseInt(parts[1].trim());
            double unitPrice = Double.parseDouble(parts[2].trim());
            double lineTotal = quantity * unitPrice;
            orderTotal += lineTotal;

            Map<String, Object> lineItem = new LinkedHashMap<>();
            lineItem.put("sku", sku);
            lineItem.put("quantity", quantity);
            lineItem.put("unitPrice", unitPrice);
            lineItem.put("lineTotal", Math.round(lineTotal * 100.0) / 100.0);
            lineItems.add(lineItem);
        }

        if (lineItems.isEmpty()) {
            return KubeFnResponse.badRequest(Map.of(
                    "error", "invalid_order",
                    "message", "At least one line item is required"
            ));
        }

        String orderId = "ORD-" + System.nanoTime() % 100000;

        Map<String, Object> order = new LinkedHashMap<>();
        order.put("orderId", orderId);
        order.put("customerId", customerId);
        order.put("status", "CREATED");
        order.put("lineItems", lineItems);
        order.put("itemCount", lineItems.size());
        order.put("orderTotal", Math.round(orderTotal * 100.0) / 100.0);
        order.put("currency", "USD");
        order.put("shippingAddress", shippingAddress);
        order.put("createdAt", System.currentTimeMillis());

        ctx.heap().publish("saga:order", order, Map.class);
        ctx.heap().publish("saga:step-log", new ArrayList<>(List.of(
                Map.of("step", "order_creation", "status", "completed",
                        "orderId", orderId, "timestamp", System.currentTimeMillis())
        )), List.class);

        return KubeFnResponse.ok(order);
    }
}

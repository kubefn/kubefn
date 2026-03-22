package com.enterprise.saga;

import io.kubefn.api.*;

import java.util.*;

/**
 * Captures payment for the order. Simulates a payment gateway call with
 * fraud screening. Orders over $1000 are flagged and will fail to simulate
 * the saga compensation flow.
 */
@FnRoute(path = "/saga/capture", methods = {"POST"})
@FnGroup("order-saga")
public class PaymentCaptureFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    private static final double HIGH_VALUE_THRESHOLD = 1000.00;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var order = ctx.heap().get("saga:order", Map.class).orElse(Map.of());
        String orderId = (String) order.getOrDefault("orderId", "unknown");
        double orderTotal = ((Number) order.getOrDefault("orderTotal", 0.0)).doubleValue();
        String customerId = (String) order.getOrDefault("customerId", "unknown");

        String paymentMethod = request.queryParam("paymentMethod").orElse("credit_card");
        String cardLast4 = request.queryParam("cardLast4").orElse("4242");

        // Simulate fraud screening
        boolean highValue = orderTotal > HIGH_VALUE_THRESHOLD;
        String riskLevel = highValue ? "HIGH" : (orderTotal > 500 ? "MEDIUM" : "LOW");

        // Simulate payment failure for orders > $1000 (triggers compensation)
        if (highValue) {
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("orderId", orderId);
            failure.put("status", "PAYMENT_DECLINED");
            failure.put("reason", "high_value_fraud_hold");
            failure.put("amount", orderTotal);
            failure.put("riskLevel", riskLevel);
            failure.put("message", "Payment declined: order amount $" +
                    String.format("%.2f", orderTotal) +
                    " exceeds fraud screening threshold of $" +
                    String.format("%.2f", HIGH_VALUE_THRESHOLD));
            failure.put("transactionId", null);
            failure.put("timestamp", System.currentTimeMillis());

            ctx.heap().publish("saga:payment", failure, Map.class);

            var stepLog = ctx.heap().get("saga:step-log", List.class).orElse(new ArrayList<>());
            List<Map<String, Object>> updatedLog = new ArrayList<>(stepLog);
            updatedLog.add(Map.of(
                    "step", "payment_capture",
                    "status", "failed",
                    "orderId", orderId,
                    "reason", "high_value_fraud_hold",
                    "timestamp", System.currentTimeMillis()
            ));
            ctx.heap().publish("saga:step-log", updatedLog, List.class);

            return KubeFnResponse.status(402).body(failure);
        }

        // Successful payment
        String transactionId = "TXN-" + System.nanoTime() % 100000;

        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("orderId", orderId);
        payment.put("transactionId", transactionId);
        payment.put("status", "PAYMENT_CAPTURED");
        payment.put("amount", orderTotal);
        payment.put("currency", "USD");
        payment.put("paymentMethod", paymentMethod);
        payment.put("cardLast4", cardLast4);
        payment.put("customerId", customerId);
        payment.put("riskLevel", riskLevel);
        payment.put("authorizationCode", "AUTH-" + (int) (Math.random() * 999999));
        payment.put("processorResponse", "APPROVED");
        payment.put("timestamp", System.currentTimeMillis());

        ctx.heap().publish("saga:payment", payment, Map.class);

        var stepLog = ctx.heap().get("saga:step-log", List.class).orElse(new ArrayList<>());
        List<Map<String, Object>> updatedLog = new ArrayList<>(stepLog);
        updatedLog.add(Map.of(
                "step", "payment_capture",
                "status", "completed",
                "orderId", orderId,
                "transactionId", transactionId,
                "timestamp", System.currentTimeMillis()
        ));
        ctx.heap().publish("saga:step-log", updatedLog, List.class);

        return KubeFnResponse.ok(payment);
    }
}

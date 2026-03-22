package com.enterprise;

import com.kubefn.api.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ingests raw transaction data, normalizes it, and publishes enriched
 * transaction context to HeapExchange for downstream fraud signals.
 */
@FnRoute(path = "/fraud/analyze", methods = {"POST"})
@FnGroup("fraud-engine")
public class TransactionIngestFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        String body = request.bodyAsString();

        // Parse transaction fields from request body
        String txnId = extractField(body, "transactionId", "TXN-" + System.nanoTime());
        String userId = extractField(body, "userId", "unknown");
        double amount = Double.parseDouble(extractField(body, "amount", "0.0"));
        String currency = extractField(body, "currency", "USD");
        String merchantId = extractField(body, "merchantId", "MERCH-000");
        String merchantCategory = extractField(body, "merchantCategory", "general");
        String country = extractField(body, "country", "US");
        String cardLast4 = extractField(body, "cardLast4", "0000");
        String ipAddress = extractField(body, "ipAddress", "0.0.0.0");
        String deviceId = extractField(body, "deviceId", "unknown-device");

        // Normalize amount to USD for consistent scoring
        double normalizedAmount = normalizeToUsd(amount, currency);

        // Classify transaction size tier
        String sizeTier;
        if (normalizedAmount < 50) sizeTier = "micro";
        else if (normalizedAmount < 500) sizeTier = "standard";
        else if (normalizedAmount < 5000) sizeTier = "large";
        else sizeTier = "high-value";

        // Determine if cross-border
        boolean crossBorder = !"US".equalsIgnoreCase(country);

        // Build enriched transaction context
        Map<String, Object> txn = new LinkedHashMap<>();
        txn.put("transactionId", txnId);
        txn.put("userId", userId);
        txn.put("originalAmount", amount);
        txn.put("currency", currency);
        txn.put("normalizedAmountUsd", normalizedAmount);
        txn.put("sizeTier", sizeTier);
        txn.put("merchantId", merchantId);
        txn.put("merchantCategory", merchantCategory);
        txn.put("country", country);
        txn.put("crossBorder", crossBorder);
        txn.put("cardLast4", cardLast4);
        txn.put("ipAddress", ipAddress);
        txn.put("deviceId", deviceId);
        txn.put("ingestTimestamp", System.currentTimeMillis());

        ctx.heap().publish("fraud:transaction", txn, Map.class);

        return KubeFnResponse.ok(txn);
    }

    private double normalizeToUsd(double amount, String currency) {
        return switch (currency.toUpperCase()) {
            case "EUR" -> amount * 1.08;
            case "GBP" -> amount * 1.27;
            case "JPY" -> amount * 0.0067;
            case "CAD" -> amount * 0.74;
            case "AUD" -> amount * 0.65;
            case "INR" -> amount * 0.012;
            default -> amount;
        };
    }

    private String extractField(String body, String key, String defaultValue) {
        if (body == null || body.isEmpty()) return defaultValue;
        String search = "\"" + key + "\"";
        int idx = body.indexOf(search);
        if (idx < 0) return defaultValue;
        int colonIdx = body.indexOf(':', idx + search.length());
        if (colonIdx < 0) return defaultValue;
        int start = colonIdx + 1;
        while (start < body.length() && (body.charAt(start) == ' ' || body.charAt(start) == '"')) start++;
        int end = start;
        while (end < body.length() && body.charAt(end) != '"' && body.charAt(end) != ','
                && body.charAt(end) != '}') end++;
        return body.substring(start, end).trim();
    }
}

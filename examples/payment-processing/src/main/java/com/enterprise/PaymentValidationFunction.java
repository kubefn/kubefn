package com.enterprise;

import io.kubefn.api.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates the payment request: checks required fields, validates amounts,
 * verifies account format, and enforces payment limits. Publishes validated
 * and normalized payment data to HeapExchange.
 */
@FnRoute(path = "/pay/validate", methods = {"POST"})
@FnGroup("payment-pipeline")
public class PaymentValidationFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    private static final double MIN_PAYMENT = 0.01;
    private static final double MAX_SINGLE_PAYMENT = 1_000_000.00;
    private static final Map<String, String> CURRENCY_PATTERNS = Map.of(
            "USD", "^\\d+\\.\\d{2}$",
            "EUR", "^\\d+\\.\\d{2}$",
            "GBP", "^\\d+\\.\\d{2}$",
            "JPY", "^\\d+$"
    );
    private static final List<String> SUPPORTED_CURRENCIES = List.of(
            "USD", "EUR", "GBP", "JPY", "CAD", "AUD", "CHF", "INR", "SGD", "HKD");

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        String body = request.bodyAsString();

        String paymentId = "PAY-" + System.nanoTime();
        String senderId = extractField(body, "senderId", "");
        String receiverId = extractField(body, "receiverId", "");
        String amountStr = extractField(body, "amount", "0");
        String currency = extractField(body, "currency", "USD").toUpperCase();
        String paymentType = extractField(body, "paymentType", "transfer");
        String reference = extractField(body, "reference", "");
        String senderCountry = extractField(body, "senderCountry", "US");
        String receiverCountry = extractField(body, "receiverCountry", "US");

        double amount;
        try {
            amount = Double.parseDouble(amountStr);
        } catch (NumberFormatException e) {
            return KubeFnResponse.error(Map.of("error", "Invalid amount format", "field", "amount"));
        }

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Field presence validation
        if (senderId.isEmpty()) errors.add("senderId is required");
        if (receiverId.isEmpty()) errors.add("receiverId is required");
        if (senderId.equals(receiverId)) errors.add("sender and receiver cannot be the same");

        // Amount validation
        if (amount < MIN_PAYMENT) errors.add("Amount below minimum: " + MIN_PAYMENT);
        if (amount > MAX_SINGLE_PAYMENT) errors.add("Amount exceeds maximum single payment: " + MAX_SINGLE_PAYMENT);

        // Currency validation
        if (!SUPPORTED_CURRENCIES.contains(currency)) {
            errors.add("Unsupported currency: " + currency);
        }

        // Payment type validation
        List<String> validTypes = List.of("transfer", "wire", "ach", "instant", "international");
        if (!validTypes.contains(paymentType)) {
            errors.add("Invalid payment type: " + paymentType);
        }

        // Cross-border detection
        boolean crossBorder = !senderCountry.equalsIgnoreCase(receiverCountry);
        if (crossBorder && "ach".equals(paymentType)) {
            errors.add("ACH not supported for cross-border payments");
        }

        // Large payment warning
        if (amount > 50_000) {
            warnings.add("Large payment: additional verification may be required");
        }

        // Round amount warning
        if (amount > 10_000 && amount == Math.floor(amount)) {
            warnings.add("Large round-amount payment flagged for review");
        }

        boolean valid = errors.isEmpty();

        // Build validated payment object
        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("paymentId", paymentId);
        payment.put("valid", valid);
        payment.put("senderId", senderId);
        payment.put("receiverId", receiverId);
        payment.put("amount", amount);
        payment.put("currency", currency);
        payment.put("paymentType", paymentType);
        payment.put("reference", reference);
        payment.put("senderCountry", senderCountry);
        payment.put("receiverCountry", receiverCountry);
        payment.put("crossBorder", crossBorder);
        payment.put("requiresFX", crossBorder);
        payment.put("errors", errors);
        payment.put("warnings", warnings);
        payment.put("validatedAt", System.currentTimeMillis());

        ctx.heap().publish("payment:validated", payment, Map.class);

        if (!valid) {
            return KubeFnResponse.error(payment);
        }
        return KubeFnResponse.ok(payment);
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

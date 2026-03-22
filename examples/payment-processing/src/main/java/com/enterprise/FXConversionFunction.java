package com.enterprise;

import io.kubefn.api.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Handles foreign exchange conversion for cross-border payments.
 * Applies bid/ask spreads, computes conversion with markup,
 * and publishes the FX quote to HeapExchange.
 */
@FnRoute(path = "/pay/fx", methods = {"POST"})
@FnGroup("payment-pipeline")
public class FXConversionFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    // Mid-market exchange rates (simulated — base currency USD)
    private static final Map<String, Double> MID_RATES = Map.ofEntries(
            Map.entry("USD", 1.0),
            Map.entry("EUR", 0.9259),
            Map.entry("GBP", 0.7874),
            Map.entry("JPY", 149.50),
            Map.entry("CAD", 1.3514),
            Map.entry("AUD", 1.5385),
            Map.entry("CHF", 0.8772),
            Map.entry("INR", 83.12),
            Map.entry("SGD", 1.3425),
            Map.entry("HKD", 7.8265)
    );

    // Spread tiers based on amount
    private static final double SPREAD_TIER1 = 0.0050;   // < $1,000: 50 bps
    private static final double SPREAD_TIER2 = 0.0030;   // $1K-$50K: 30 bps
    private static final double SPREAD_TIER3 = 0.0015;   // $50K-$500K: 15 bps
    private static final double SPREAD_TIER4 = 0.0008;   // > $500K: 8 bps

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var payment = ctx.heap().get("payment:validated", Map.class).orElse(Map.of());
        double amount = ((Number) payment.getOrDefault("amount", 0.0)).doubleValue();
        String sourceCurrency = (String) payment.getOrDefault("currency", "USD");
        boolean crossBorder = Boolean.TRUE.equals(payment.get("crossBorder"));

        // Determine target currency based on receiver country
        String receiverCountry = (String) payment.getOrDefault("receiverCountry", "US");
        String targetCurrency = mapCountryToCurrency(receiverCountry);

        // If same currency, no conversion needed
        if (sourceCurrency.equals(targetCurrency) || !crossBorder) {
            Map<String, Object> noFx = new LinkedHashMap<>();
            noFx.put("required", false);
            noFx.put("sourceCurrency", sourceCurrency);
            noFx.put("targetCurrency", sourceCurrency);
            noFx.put("sourceAmount", amount);
            noFx.put("targetAmount", amount);
            noFx.put("rate", 1.0);
            noFx.put("reason", "Same currency — no conversion needed");

            ctx.heap().publish("payment:fx", noFx, Map.class);
            return KubeFnResponse.ok(noFx);
        }

        // Get mid-market rates
        double sourceToUsd = 1.0 / MID_RATES.getOrDefault(sourceCurrency, 1.0);
        double usdToTarget = MID_RATES.getOrDefault(targetCurrency, 1.0);
        double midRate = sourceToUsd * usdToTarget;

        // Apply spread based on amount tier
        double amountInUsd = amount * sourceToUsd;
        double spread = computeSpread(amountInUsd);

        // Customer gets a slightly worse rate (we sell target currency at ask)
        double customerRate = midRate * (1 + spread);

        // Compute converted amount
        double targetAmount = amount * customerRate;

        // Fee calculation
        double fxFeePercent = Math.max(0.001, spread * 0.5); // 50% of spread as explicit fee
        double fxFeeAmount = amount * fxFeePercent;

        // Quote validity
        long quoteTimestamp = System.currentTimeMillis();
        long quoteExpiresAt = quoteTimestamp + 30_000; // 30 second validity

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("required", true);
        result.put("sourceCurrency", sourceCurrency);
        result.put("targetCurrency", targetCurrency);
        result.put("sourceAmount", round(amount));
        result.put("targetAmount", round(targetAmount));
        result.put("rates", Map.of(
                "midMarket", round6(midRate),
                "customerRate", round6(customerRate),
                "spread", round6(spread),
                "spreadBps", Math.round(spread * 10000)
        ));
        result.put("fees", Map.of(
                "fxFeePercent", round(fxFeePercent * 100),
                "fxFeeAmount", round(fxFeeAmount),
                "feeCurrency", sourceCurrency
        ));
        result.put("quoteId", "FXQ-" + quoteTimestamp);
        result.put("quotedAt", quoteTimestamp);
        result.put("expiresAt", quoteExpiresAt);
        result.put("quoteValidSeconds", 30);

        ctx.heap().publish("payment:fx", result, Map.class);

        return KubeFnResponse.ok(result);
    }

    private double computeSpread(double amountUsd) {
        if (amountUsd > 500_000) return SPREAD_TIER4;
        if (amountUsd > 50_000) return SPREAD_TIER3;
        if (amountUsd > 1_000) return SPREAD_TIER2;
        return SPREAD_TIER1;
    }

    private String mapCountryToCurrency(String countryCode) {
        return switch (countryCode.toUpperCase()) {
            case "US" -> "USD";
            case "GB", "UK" -> "GBP";
            case "DE", "FR", "IT", "ES", "NL", "BE", "AT", "IE", "FI", "PT" -> "EUR";
            case "JP" -> "JPY";
            case "CA" -> "CAD";
            case "AU" -> "AUD";
            case "CH" -> "CHF";
            case "IN" -> "INR";
            case "SG" -> "SGD";
            case "HK" -> "HKD";
            default -> "USD";
        };
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private double round6(double v) {
        return Math.round(v * 1_000_000.0) / 1_000_000.0;
    }
}

package com.enterprise;

import io.kubefn.api.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Analyzes user spending behavior patterns against the current transaction.
 * Detects velocity anomalies, unusual amounts, time-of-day patterns,
 * and merchant category deviations.
 */
@FnRoute(path = "/fraud/behavior", methods = {"POST"})
@FnGroup("fraud-engine")
public class BehaviorAnalysisFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var txn = ctx.heap().get("fraud:transaction", Map.class).orElse(Map.of());
        String userId = (String) txn.getOrDefault("userId", "unknown");
        double amount = ((Number) txn.getOrDefault("normalizedAmountUsd", 0.0)).doubleValue();
        String merchantCategory = (String) txn.getOrDefault("merchantCategory", "general");

        // Simulate historical profile lookup (would come from a real data store)
        var profile = buildUserProfile(userId);
        double avgTxnAmount = (double) profile.get("avgTransactionAmount");
        double maxTxnAmount = (double) profile.get("maxTransactionAmount");
        int dailyTxnCount = (int) profile.get("avgDailyTransactions");
        @SuppressWarnings("unchecked")
        List<String> frequentCategories = (List<String>) profile.get("frequentCategories");

        double behaviorScore = 0.0;
        var anomalies = new java.util.ArrayList<String>();

        // Amount deviation check — how far is this from user's average?
        double amountRatio = amount / Math.max(avgTxnAmount, 1.0);
        if (amountRatio > 5.0) {
            behaviorScore += 0.30;
            anomalies.add("amount_5x_average");
        } else if (amountRatio > 3.0) {
            behaviorScore += 0.20;
            anomalies.add("amount_3x_average");
        } else if (amountRatio > 2.0) {
            behaviorScore += 0.10;
            anomalies.add("amount_2x_average");
        }

        // All-time max breach
        if (amount > maxTxnAmount * 1.5) {
            behaviorScore += 0.20;
            anomalies.add("exceeds_historical_max");
        }

        // Merchant category deviation
        boolean unusualCategory = !frequentCategories.contains(merchantCategory);
        if (unusualCategory) {
            behaviorScore += 0.15;
            anomalies.add("unusual_merchant_category:" + merchantCategory);
        }

        // Time-of-day analysis (simulated — use hour from system clock)
        int hour = java.time.LocalTime.now().getHour();
        boolean offHours = hour < 6 || hour > 23;
        if (offHours) {
            behaviorScore += 0.10;
            anomalies.add("off_hours_transaction");
        }

        // Velocity check — simulate current-day count
        int simulatedTodayCount = (int) (Math.abs(userId.hashCode()) % 8) + 1;
        if (simulatedTodayCount > dailyTxnCount * 2) {
            behaviorScore += 0.20;
            anomalies.add("velocity_spike:today=" + simulatedTodayCount + "_vs_avg=" + dailyTxnCount);
        }

        // Round-number check — fraudsters often use round amounts
        boolean roundAmount = amount == Math.floor(amount) && amount >= 100;
        if (roundAmount) {
            behaviorScore += 0.05;
            anomalies.add("round_amount");
        }

        behaviorScore = Math.min(behaviorScore, 1.0);

        String riskLevel;
        if (behaviorScore < 0.15) riskLevel = "normal";
        else if (behaviorScore < 0.4) riskLevel = "elevated";
        else if (behaviorScore < 0.7) riskLevel = "suspicious";
        else riskLevel = "highly_anomalous";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("behaviorScore", Math.round(behaviorScore * 1000.0) / 1000.0);
        result.put("riskLevel", riskLevel);
        result.put("transactionAmount", amount);
        result.put("userAvgAmount", avgTxnAmount);
        result.put("amountRatio", Math.round(amountRatio * 100.0) / 100.0);
        result.put("unusualCategory", unusualCategory);
        result.put("offHours", offHours);
        result.put("todayTxnCount", simulatedTodayCount);
        result.put("anomalies", anomalies);

        ctx.heap().publish("fraud:behavior", result, Map.class);

        return KubeFnResponse.ok(result);
    }

    private Map<String, Object> buildUserProfile(String userId) {
        // Deterministic profile based on user ID for reproducible demos
        long hash = Math.abs(userId.hashCode());
        double avgAmount = 50.0 + (hash % 200);
        double maxAmount = avgAmount * 3 + (hash % 500);
        int avgDaily = 2 + (int) (hash % 5);

        List<String> categories;
        if (hash % 3 == 0) {
            categories = List.of("grocery", "restaurant", "gas", "general");
        } else if (hash % 3 == 1) {
            categories = List.of("electronics", "subscription", "travel", "general");
        } else {
            categories = List.of("clothing", "restaurant", "entertainment", "general");
        }

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("avgTransactionAmount", avgAmount);
        profile.put("maxTransactionAmount", maxAmount);
        profile.put("avgDailyTransactions", avgDaily);
        profile.put("frequentCategories", categories);
        profile.put("accountAgeMonths", 6 + (int) (hash % 60));
        return profile;
    }
}

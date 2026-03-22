package com.enterprise;

import com.kubefn.api.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies a configurable set of fraud detection rules against the transaction
 * and all previously computed signals. Each rule contributes a weighted score
 * and can trigger hard-block or soft-flag actions.
 */
@FnRoute(path = "/fraud/rules", methods = {"POST"})
@FnGroup("fraud-engine")
public class RuleEngineFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var txn = ctx.heap().get("fraud:transaction", Map.class).orElse(Map.of());
        var device = ctx.heap().get("fraud:device", Map.class).orElse(Map.of());
        var behavior = ctx.heap().get("fraud:behavior", Map.class).orElse(Map.of());

        double amount = ((Number) txn.getOrDefault("normalizedAmountUsd", 0.0)).doubleValue();
        boolean crossBorder = Boolean.TRUE.equals(txn.get("crossBorder"));
        String sizeTier = (String) txn.getOrDefault("sizeTier", "standard");
        String merchantCategory = (String) txn.getOrDefault("merchantCategory", "general");

        double deviceScore = ((Number) device.getOrDefault("deviceScore", 0.0)).doubleValue();
        boolean geoMismatch = Boolean.TRUE.equals(device.get("geoMismatch"));
        boolean vpnDetected = Boolean.TRUE.equals(device.get("vpnDetected"));

        double behaviorScore = ((Number) behavior.getOrDefault("behaviorScore", 0.0)).doubleValue();
        boolean unusualCategory = Boolean.TRUE.equals(behavior.get("unusualCategory"));

        List<Map<String, Object>> ruleResults = new ArrayList<>();
        double totalScore = 0.0;
        boolean hardBlock = false;

        // Rule 1: High-value cross-border transaction
        if (crossBorder && amount > 2000) {
            double score = 0.35;
            totalScore += score;
            ruleResults.add(ruleResult("CROSS_BORDER_HIGH_VALUE",
                    "Cross-border transaction over $2000", score, "flag"));
        }

        // Rule 2: VPN + geo mismatch (strong signal)
        if (vpnDetected && geoMismatch) {
            double score = 0.40;
            totalScore += score;
            ruleResults.add(ruleResult("VPN_GEO_MISMATCH",
                    "VPN detected with geographic mismatch", score, "block"));
            hardBlock = true;
        }

        // Rule 3: Amount threshold — instant block on extreme values
        if (amount > 10000) {
            double score = 0.50;
            totalScore += score;
            ruleResults.add(ruleResult("EXTREME_AMOUNT",
                    "Transaction exceeds $10,000 threshold", score, "block"));
            hardBlock = true;
        } else if (amount > 5000) {
            double score = 0.25;
            totalScore += score;
            ruleResults.add(ruleResult("HIGH_AMOUNT",
                    "Transaction exceeds $5,000 threshold", score, "flag"));
        }

        // Rule 4: Behavior anomaly + new device combo
        boolean newDevice = Boolean.TRUE.equals(device.get("newDevice"));
        if (behaviorScore > 0.4 && newDevice) {
            double score = 0.30;
            totalScore += score;
            ruleResults.add(ruleResult("ANOMALY_NEW_DEVICE",
                    "Behavioral anomalies on a new device", score, "flag"));
        }

        // Rule 5: High-risk merchant categories
        List<String> riskyCategories = List.of("gambling", "crypto", "wire_transfer", "gift_cards");
        if (riskyCategories.contains(merchantCategory)) {
            double score = 0.20;
            totalScore += score;
            ruleResults.add(ruleResult("HIGH_RISK_MERCHANT",
                    "Transaction in high-risk merchant category: " + merchantCategory, score, "flag"));
        }

        // Rule 6: Compound signal — multiple moderate signals
        int moderateSignals = 0;
        if (deviceScore > 0.3) moderateSignals++;
        if (behaviorScore > 0.3) moderateSignals++;
        if (crossBorder) moderateSignals++;
        if (unusualCategory) moderateSignals++;
        if (moderateSignals >= 3) {
            double score = 0.25;
            totalScore += score;
            ruleResults.add(ruleResult("COMPOUND_MODERATE_SIGNALS",
                    moderateSignals + " moderate risk signals detected simultaneously", score, "flag"));
        }

        // Rule 7: Micro-transaction pattern (potential card testing)
        if ("micro".equals(sizeTier) && newDevice) {
            double score = 0.15;
            totalScore += score;
            ruleResults.add(ruleResult("CARD_TESTING_PATTERN",
                    "Micro transaction from new device — potential card testing", score, "flag"));
        }

        totalScore = Math.min(totalScore, 1.0);

        String action;
        if (hardBlock) action = "BLOCK";
        else if (totalScore > 0.6) action = "MANUAL_REVIEW";
        else if (totalScore > 0.3) action = "FLAG";
        else action = "PASS";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ruleScore", Math.round(totalScore * 1000.0) / 1000.0);
        result.put("action", action);
        result.put("hardBlock", hardBlock);
        result.put("rulesTriggered", ruleResults.size());
        result.put("rulesEvaluated", 7);
        result.put("ruleResults", ruleResults);

        ctx.heap().publish("fraud:rules", result, Map.class);

        return KubeFnResponse.ok(result);
    }

    private Map<String, Object> ruleResult(String ruleId, String description,
                                            double score, String action) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ruleId", ruleId);
        r.put("description", description);
        r.put("score", score);
        r.put("action", action);
        return r;
    }
}

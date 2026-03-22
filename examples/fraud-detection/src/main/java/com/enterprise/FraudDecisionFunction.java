package com.enterprise;

import io.kubefn.api.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the complete fraud detection pipeline. Invokes all upstream
 * functions via ctx.getFunction(), reads their heap-published results,
 * and assembles a comprehensive fraud decision report with a composite score.
 */
@FnRoute(path = "/fraud/decide", methods = {"POST"})
@FnGroup("fraud-engine")
public class FraudDecisionFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    // Weight each signal source for the composite score
    private static final double WEIGHT_DEVICE = 0.20;
    private static final double WEIGHT_BEHAVIOR = 0.20;
    private static final double WEIGHT_RULES = 0.25;
    private static final double WEIGHT_ML = 0.35;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        long startNanos = System.nanoTime();

        // Step 1: Execute the full pipeline — all in-memory, zero-copy
        ctx.getFunction(TransactionIngestFunction.class).handle(request);

        long afterIngest = System.nanoTime();
        ctx.getFunction(DeviceFingerprintFunction.class).handle(request);

        long afterDevice = System.nanoTime();
        ctx.getFunction(BehaviorAnalysisFunction.class).handle(request);

        long afterBehavior = System.nanoTime();
        ctx.getFunction(RuleEngineFunction.class).handle(request);

        long afterRules = System.nanoTime();
        ctx.getFunction(MLScoringFunction.class).handle(request);

        long afterML = System.nanoTime();

        // Step 2: Read all results from HeapExchange — zero-copy
        var txn = ctx.heap().get("fraud:transaction", Map.class).orElse(Map.of());
        var device = ctx.heap().get("fraud:device", Map.class).orElse(Map.of());
        var behavior = ctx.heap().get("fraud:behavior", Map.class).orElse(Map.of());
        var rules = ctx.heap().get("fraud:rules", Map.class).orElse(Map.of());
        var mlScore = ctx.heap().get("fraud:mlScore", Map.class).orElse(Map.of());

        // Step 3: Compute composite fraud score
        double deviceScoreVal = ((Number) device.getOrDefault("deviceScore", 0.0)).doubleValue();
        double behaviorScoreVal = ((Number) behavior.getOrDefault("behaviorScore", 0.0)).doubleValue();
        double ruleScoreVal = ((Number) rules.getOrDefault("ruleScore", 0.0)).doubleValue();
        double mlProbability = ((Number) mlScore.getOrDefault("fraudProbability", 0.0)).doubleValue();

        double compositeScore = (WEIGHT_DEVICE * deviceScoreVal)
                + (WEIGHT_BEHAVIOR * behaviorScoreVal)
                + (WEIGHT_RULES * ruleScoreVal)
                + (WEIGHT_ML * mlProbability);
        compositeScore = Math.min(compositeScore, 1.0);

        // Step 4: Make final decision
        boolean hardBlock = Boolean.TRUE.equals(rules.get("hardBlock"));
        String decision;
        List<String> actions = new ArrayList<>();

        if (hardBlock) {
            decision = "BLOCKED";
            actions.add("transaction_declined");
            actions.add("alert_fraud_team");
            actions.add("lock_card_temporary");
        } else if (compositeScore > 0.70) {
            decision = "DECLINED";
            actions.add("transaction_declined");
            actions.add("notify_customer");
            actions.add("escalate_to_analyst");
        } else if (compositeScore > 0.45) {
            decision = "MANUAL_REVIEW";
            actions.add("transaction_held");
            actions.add("queue_for_review");
            actions.add("request_step_up_auth");
        } else if (compositeScore > 0.25) {
            decision = "APPROVED_WITH_MONITORING";
            actions.add("transaction_approved");
            actions.add("enable_enhanced_monitoring");
        } else {
            decision = "APPROVED";
            actions.add("transaction_approved");
        }

        // Step 5: Build score breakdown
        Map<String, Object> scoreBreakdown = new LinkedHashMap<>();
        scoreBreakdown.put("deviceSignal", Map.of(
                "score", deviceScoreVal, "weight", WEIGHT_DEVICE,
                "contribution", round(WEIGHT_DEVICE * deviceScoreVal)));
        scoreBreakdown.put("behaviorSignal", Map.of(
                "score", behaviorScoreVal, "weight", WEIGHT_BEHAVIOR,
                "contribution", round(WEIGHT_BEHAVIOR * behaviorScoreVal)));
        scoreBreakdown.put("ruleEngineSignal", Map.of(
                "score", ruleScoreVal, "weight", WEIGHT_RULES,
                "contribution", round(WEIGHT_RULES * ruleScoreVal)));
        scoreBreakdown.put("mlModelSignal", Map.of(
                "score", mlProbability, "weight", WEIGHT_ML,
                "contribution", round(WEIGHT_ML * mlProbability)));

        long endNanos = System.nanoTime();
        double totalMs = (endNanos - startNanos) / 1_000_000.0;

        // Step 6: Assemble the comprehensive fraud report
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("transactionId", txn.getOrDefault("transactionId", "unknown"));
        report.put("decision", decision);
        report.put("compositeScore", round(compositeScore));
        report.put("actions", actions);
        report.put("scoreBreakdown", scoreBreakdown);
        report.put("signals", Map.of(
                "transaction", txn,
                "device", device,
                "behavior", behavior,
                "ruleEngine", rules,
                "mlModel", mlScore
        ));
        report.put("_meta", Map.of(
                "pipelineSteps", 6,
                "totalTimeMs", String.format("%.3f", totalMs),
                "totalTimeNanos", endNanos - startNanos,
                "stepTimings", Map.of(
                        "ingestMs", formatMs(afterIngest - startNanos),
                        "deviceMs", formatMs(afterDevice - afterIngest),
                        "behaviorMs", formatMs(afterBehavior - afterDevice),
                        "rulesMs", formatMs(afterRules - afterBehavior),
                        "mlScoringMs", formatMs(afterML - afterRules),
                        "assemblyMs", formatMs(endNanos - afterML)
                ),
                "heapObjectsUsed", ctx.heap().keys().size(),
                "zeroCopy", true,
                "note", "6 fraud signals composed in-memory. No HTTP calls. No serialization."
        ));

        return KubeFnResponse.ok(report);
    }

    private double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private String formatMs(long nanos) {
        return String.format("%.3f", nanos / 1_000_000.0);
    }
}

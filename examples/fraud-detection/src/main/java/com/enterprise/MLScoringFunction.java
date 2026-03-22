package com.enterprise;

import io.kubefn.api.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Simulates ML model inference for fraud detection. Computes a probability
 * score using a logistic-regression-style feature combination across
 * transaction, device, and behavioral signals. In production, this would
 * call an embedded ONNX/PMML model or an inference endpoint.
 */
@FnRoute(path = "/fraud/ml-score", methods = {"POST"})
@FnGroup("fraud-engine")
public class MLScoringFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    // Simulated model weights (would be loaded from model artifact)
    private static final double W_AMOUNT = 0.0003;
    private static final double W_DEVICE = 1.2;
    private static final double W_BEHAVIOR = 1.5;
    private static final double W_CROSS_BORDER = 0.8;
    private static final double W_GEO_MISMATCH = 1.0;
    private static final double W_VPN = 0.9;
    private static final double W_NEW_DEVICE = 0.6;
    private static final double W_VELOCITY = 0.4;
    private static final double BIAS = -2.5;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var txn = ctx.heap().get("fraud:transaction", Map.class).orElse(Map.of());
        var device = ctx.heap().get("fraud:device", Map.class).orElse(Map.of());
        var behavior = ctx.heap().get("fraud:behavior", Map.class).orElse(Map.of());

        // Extract feature vector
        double amount = ((Number) txn.getOrDefault("normalizedAmountUsd", 0.0)).doubleValue();
        boolean crossBorder = Boolean.TRUE.equals(txn.get("crossBorder"));
        double deviceScore = ((Number) device.getOrDefault("deviceScore", 0.0)).doubleValue();
        boolean geoMismatch = Boolean.TRUE.equals(device.get("geoMismatch"));
        boolean vpnDetected = Boolean.TRUE.equals(device.get("vpnDetected"));
        boolean newDevice = Boolean.TRUE.equals(device.get("newDevice"));
        double behaviorScore = ((Number) behavior.getOrDefault("behaviorScore", 0.0)).doubleValue();
        double amountRatio = ((Number) behavior.getOrDefault("amountRatio", 1.0)).doubleValue();

        // Compute logit (linear combination of features)
        double logit = BIAS
                + W_AMOUNT * amount
                + W_DEVICE * deviceScore
                + W_BEHAVIOR * behaviorScore
                + W_CROSS_BORDER * (crossBorder ? 1.0 : 0.0)
                + W_GEO_MISMATCH * (geoMismatch ? 1.0 : 0.0)
                + W_VPN * (vpnDetected ? 1.0 : 0.0)
                + W_NEW_DEVICE * (newDevice ? 1.0 : 0.0)
                + W_VELOCITY * Math.min(amountRatio / 5.0, 1.0);

        // Sigmoid activation
        double fraudProbability = 1.0 / (1.0 + Math.exp(-logit));

        // Model confidence based on feature completeness
        int featuresPresent = 0;
        if (amount > 0) featuresPresent++;
        if (deviceScore > 0) featuresPresent++;
        if (behaviorScore > 0) featuresPresent++;
        if (!txn.isEmpty()) featuresPresent++;
        if (!device.isEmpty()) featuresPresent++;
        if (!behavior.isEmpty()) featuresPresent++;
        double confidence = featuresPresent / 6.0;

        // Classify
        String classification;
        if (fraudProbability > 0.85) classification = "FRAUD";
        else if (fraudProbability > 0.60) classification = "SUSPICIOUS";
        else if (fraudProbability > 0.35) classification = "UNCERTAIN";
        else classification = "LEGITIMATE";

        // Feature importance (approximate by contribution magnitude)
        Map<String, Object> featureImportance = new LinkedHashMap<>();
        featureImportance.put("amount", round(W_AMOUNT * amount));
        featureImportance.put("deviceScore", round(W_DEVICE * deviceScore));
        featureImportance.put("behaviorScore", round(W_BEHAVIOR * behaviorScore));
        featureImportance.put("crossBorder", round(W_CROSS_BORDER * (crossBorder ? 1.0 : 0.0)));
        featureImportance.put("geoMismatch", round(W_GEO_MISMATCH * (geoMismatch ? 1.0 : 0.0)));
        featureImportance.put("vpn", round(W_VPN * (vpnDetected ? 1.0 : 0.0)));
        featureImportance.put("newDevice", round(W_NEW_DEVICE * (newDevice ? 1.0 : 0.0)));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("modelVersion", "fraud-lr-v3.2.1");
        result.put("fraudProbability", round(fraudProbability));
        result.put("classification", classification);
        result.put("confidence", round(confidence));
        result.put("logit", round(logit));
        result.put("featureImportance", featureImportance);

        ctx.heap().publish("fraud:mlScore", result, Map.class);

        return KubeFnResponse.ok(result);
    }

    private double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}

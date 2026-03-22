package com.enterprise;

import com.kubefn.api.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Performs Anti-Money Laundering (AML) screening against sanctions lists,
 * PEP databases, and transaction pattern analysis. Computes a risk score
 * and determines if the payment requires enhanced due diligence.
 */
@FnRoute(path = "/pay/aml", methods = {"POST"})
@FnGroup("payment-pipeline")
public class AMLScreeningFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    // Simulated sanctions/high-risk jurisdictions
    private static final List<String> HIGH_RISK_COUNTRIES = List.of(
            "KP", "IR", "SY", "CU", "VE", "MM", "BY", "RU");

    private static final List<String> MEDIUM_RISK_COUNTRIES = List.of(
            "AE", "PA", "KY", "BZ", "VG", "LB", "PK", "NG");

    // Structuring detection thresholds
    private static final double STRUCTURING_THRESHOLD = 10_000.0;
    private static final double LARGE_TRANSACTION_THRESHOLD = 50_000.0;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var payment = ctx.heap().get("payment:validated", Map.class).orElse(Map.of());
        String senderId = (String) payment.getOrDefault("senderId", "unknown");
        String receiverId = (String) payment.getOrDefault("receiverId", "unknown");
        double amount = ((Number) payment.getOrDefault("amount", 0.0)).doubleValue();
        String senderCountry = (String) payment.getOrDefault("senderCountry", "US");
        String receiverCountry = (String) payment.getOrDefault("receiverCountry", "US");
        boolean crossBorder = Boolean.TRUE.equals(payment.get("crossBorder"));

        double riskScore = 0.0;
        List<Map<String, Object>> screeningResults = new ArrayList<>();
        List<String> flags = new ArrayList<>();

        // Check 1: Sanctions list screening (sender)
        boolean senderSanctioned = HIGH_RISK_COUNTRIES.contains(senderCountry);
        if (senderSanctioned) {
            riskScore += 0.90;
            screeningResults.add(screenResult("SANCTIONS_SENDER",
                    "Sender country on sanctions list: " + senderCountry, "FAIL", 0.90));
            flags.add("sanctioned_sender_jurisdiction");
        } else if (MEDIUM_RISK_COUNTRIES.contains(senderCountry)) {
            riskScore += 0.30;
            screeningResults.add(screenResult("RISK_JURISDICTION_SENDER",
                    "Sender in medium-risk jurisdiction: " + senderCountry, "FLAG", 0.30));
            flags.add("medium_risk_sender");
        } else {
            screeningResults.add(screenResult("SANCTIONS_SENDER",
                    "Sender jurisdiction clear", "PASS", 0.0));
        }

        // Check 2: Sanctions list screening (receiver)
        boolean receiverSanctioned = HIGH_RISK_COUNTRIES.contains(receiverCountry);
        if (receiverSanctioned) {
            riskScore += 0.90;
            screeningResults.add(screenResult("SANCTIONS_RECEIVER",
                    "Receiver country on sanctions list: " + receiverCountry, "FAIL", 0.90));
            flags.add("sanctioned_receiver_jurisdiction");
        } else if (MEDIUM_RISK_COUNTRIES.contains(receiverCountry)) {
            riskScore += 0.25;
            screeningResults.add(screenResult("RISK_JURISDICTION_RECEIVER",
                    "Receiver in medium-risk jurisdiction: " + receiverCountry, "FLAG", 0.25));
            flags.add("medium_risk_receiver");
        } else {
            screeningResults.add(screenResult("SANCTIONS_RECEIVER",
                    "Receiver jurisdiction clear", "PASS", 0.0));
        }

        // Check 3: Structuring detection (amount just below reporting threshold)
        if (amount >= STRUCTURING_THRESHOLD * 0.8 && amount < STRUCTURING_THRESHOLD) {
            riskScore += 0.35;
            screeningResults.add(screenResult("STRUCTURING_DETECTION",
                    "Amount $" + amount + " is within 20% below reporting threshold",
                    "FLAG", 0.35));
            flags.add("potential_structuring");
        } else if (amount >= STRUCTURING_THRESHOLD) {
            riskScore += 0.10; // Above threshold triggers mandatory reporting, not risk per se
            screeningResults.add(screenResult("CTR_REQUIRED",
                    "Amount exceeds CTR threshold — Currency Transaction Report required",
                    "REPORT", 0.10));
            flags.add("ctr_required");
        }

        // Check 4: Large transaction screening
        if (amount >= LARGE_TRANSACTION_THRESHOLD) {
            riskScore += 0.20;
            screeningResults.add(screenResult("LARGE_TRANSACTION",
                    "Large value transaction: $" + amount, "FLAG", 0.20));
            flags.add("large_value");
        }

        // Check 5: PEP (Politically Exposed Person) check — simulated
        long senderHash = Math.abs(senderId.hashCode());
        boolean pepMatch = (senderHash % 20) == 0; // 5% PEP match rate
        if (pepMatch) {
            riskScore += 0.40;
            screeningResults.add(screenResult("PEP_CHECK",
                    "Sender matches PEP database entry", "FLAG", 0.40));
            flags.add("pep_match");
        } else {
            screeningResults.add(screenResult("PEP_CHECK", "No PEP match found", "PASS", 0.0));
        }

        // Check 6: Adverse media screening — simulated
        long receiverHash = Math.abs(receiverId.hashCode());
        boolean adverseMedia = (receiverHash % 25) == 0;
        if (adverseMedia) {
            riskScore += 0.25;
            screeningResults.add(screenResult("ADVERSE_MEDIA",
                    "Receiver flagged in adverse media screening", "FLAG", 0.25));
            flags.add("adverse_media");
        }

        riskScore = Math.min(riskScore, 1.0);

        // Determine AML decision
        String decision;
        boolean blocked = false;
        boolean requiresEDD = false;

        if (senderSanctioned || receiverSanctioned) {
            decision = "BLOCKED";
            blocked = true;
        } else if (riskScore > 0.60) {
            decision = "REQUIRES_EDD";
            requiresEDD = true;
        } else if (riskScore > 0.30) {
            decision = "ENHANCED_MONITORING";
        } else {
            decision = "CLEARED";
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("paymentId", payment.get("paymentId"));
        result.put("amlDecision", decision);
        result.put("riskScore", Math.round(riskScore * 1000.0) / 1000.0);
        result.put("blocked", blocked);
        result.put("requiresEDD", requiresEDD);
        result.put("screeningResults", screeningResults);
        result.put("flags", flags);
        result.put("screenedAt", System.currentTimeMillis());

        ctx.heap().publish("payment:aml", result, Map.class);

        return KubeFnResponse.ok(result);
    }

    private Map<String, Object> screenResult(String checkId, String description,
                                              String result, double score) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("checkId", checkId);
        r.put("description", description);
        r.put("result", result);
        r.put("riskContribution", score);
        return r;
    }
}

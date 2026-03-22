package com.enterprise.clinical;

import com.kubefn.api.*;

import java.util.*;

/**
 * Decides whether to generate a clinical alert and determines the notification
 * urgency, routing, and required acknowledgment level. Uses a multi-factor
 * scoring model combining risk scores, guideline violations, and clinical context.
 */
@FnRoute(path = "/clinical/alert-decision", methods = {"POST"})
@FnGroup("clinical-engine")
public class AlertDecisionFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    // Alert suppression rules: avoid alert fatigue
    private static final int ALERT_SCORE_THRESHOLD = 20;
    private static final int CRITICAL_ALERT_THRESHOLD = 50;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var riskScores = ctx.heap().get("clinical:risk-scores", Map.class).orElse(Map.of());
        var guidelines = ctx.heap().get("clinical:guideline-check", Map.class).orElse(Map.of());
        var vitalsData = ctx.heap().get("clinical:vitals", Map.class).orElse(Map.of());

        String patientId = (String) riskScores.getOrDefault("patientId",
                vitalsData.getOrDefault("patientId", "unknown"));
        String overallRisk = (String) riskScores.getOrDefault("overallRiskLevel", "low");

        int violationCount = ((Number) guidelines.getOrDefault("violationCount", 0)).intValue();
        int recommendationCount = ((Number) guidelines.getOrDefault("recommendationCount", 0)).intValue();
        String highestPriority = (String) guidelines.getOrDefault("highestPriority", "low");
        List<Map<String, Object>> recommendations = (List<Map<String, Object>>)
                guidelines.getOrDefault("recommendations", List.of());

        // Multi-factor alert scoring
        int alertScore = 0;
        List<String> scoringFactors = new ArrayList<>();

        // Risk level contribution
        switch (overallRisk) {
            case "critical" -> { alertScore += 40; scoringFactors.add("risk_critical(+40)"); }
            case "elevated" -> { alertScore += 25; scoringFactors.add("risk_elevated(+25)"); }
            case "moderate" -> { alertScore += 10; scoringFactors.add("risk_moderate(+10)"); }
        }

        // Guideline violation contribution
        alertScore += violationCount * 15;
        if (violationCount > 0) scoringFactors.add("guideline_violations(" + violationCount + "x15)");

        // Critical recommendation contribution
        long criticalRecs = recommendations.stream()
                .filter(r -> "critical".equals(r.get("priority")))
                .count();
        alertScore += (int) (criticalRecs * 20);
        if (criticalRecs > 0) scoringFactors.add("critical_recommendations(" + criticalRecs + "x20)");

        // High-priority recommendation contribution
        long highRecs = recommendations.stream()
                .filter(r -> "high".equals(r.get("priority")))
                .count();
        alertScore += (int) (highRecs * 8);
        if (highRecs > 0) scoringFactors.add("high_recommendations(" + highRecs + "x8)");

        // qSOFA contribution
        Map<String, Object> qsofa = (Map<String, Object>) riskScores.getOrDefault("qsofa", Map.of());
        boolean sepsisScreenPositive = Boolean.TRUE.equals(qsofa.get("sepsisScreenPositive"));
        if (sepsisScreenPositive) {
            alertScore += 20;
            scoringFactors.add("sepsis_screen_positive(+20)");
        }

        // MEWS contribution
        Map<String, Object> mews = (Map<String, Object>) riskScores.getOrDefault("mews", Map.of());
        int mewsScore = ((Number) mews.getOrDefault("score", 0)).intValue();
        if (mewsScore >= 5) { alertScore += 15; scoringFactors.add("mews_high(+15)"); }
        else if (mewsScore >= 3) { alertScore += 8; scoringFactors.add("mews_moderate(+8)"); }

        // Decision
        boolean shouldAlert = alertScore >= ALERT_SCORE_THRESHOLD;
        boolean isCritical = alertScore >= CRITICAL_ALERT_THRESHOLD;

        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("patientId", patientId);
        decision.put("alertScore", alertScore);
        decision.put("scoringFactors", scoringFactors);
        decision.put("shouldAlert", shouldAlert);

        if (shouldAlert) {
            String urgency = isCritical ? "STAT" : (alertScore >= 35 ? "URGENT" : "ROUTINE");

            // Determine notification targets
            List<String> notifyTargets = new ArrayList<>();
            notifyTargets.add("charge_nurse");
            if (isCritical || "URGENT".equals(urgency)) notifyTargets.add("attending_physician");
            if (sepsisScreenPositive) notifyTargets.add("sepsis_response_team");
            if (criticalRecs > 0) notifyTargets.add("rapid_response_team");

            // Determine required acknowledgment
            String ackRequired = isCritical ? "physician_within_15min" :
                    ("URGENT".equals(urgency) ? "nurse_within_30min" : "nurse_within_60min");

            Map<String, Object> alert = new LinkedHashMap<>();
            alert.put("alertId", "CLA-" + System.nanoTime() % 1000000);
            alert.put("urgency", urgency);
            alert.put("notifyTargets", notifyTargets);
            alert.put("acknowledgmentRequired", ackRequired);
            alert.put("summary", buildAlertSummary(patientId, overallRisk, violationCount,
                    (int) criticalRecs, sepsisScreenPositive));
            alert.put("keyFindings", extractKeyFindings(riskScores, guidelines));

            decision.put("alert", alert);
        } else {
            decision.put("suppressionReason", "Alert score " + alertScore +
                    " below threshold " + ALERT_SCORE_THRESHOLD);
        }

        decision.put("decisionTimestamp", System.currentTimeMillis());

        ctx.heap().publish("clinical:alert-decision", decision, Map.class);
        return KubeFnResponse.ok(decision);
    }

    private String buildAlertSummary(String patientId, String riskLevel, int violations,
                                      int criticalRecs, boolean sepsisScreen) {
        StringBuilder sb = new StringBuilder();
        sb.append("Patient ").append(patientId).append(": ");
        sb.append("Risk=").append(riskLevel.toUpperCase());
        if (violations > 0) sb.append(", ").append(violations).append(" guideline violation(s)");
        if (criticalRecs > 0) sb.append(", ").append(criticalRecs).append(" critical recommendation(s)");
        if (sepsisScreen) sb.append(", SEPSIS SCREEN POSITIVE");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private List<String> extractKeyFindings(Map<String, Object> riskScores,
                                             Map<String, Object> guidelines) {
        List<String> findings = new ArrayList<>();

        Map<String, Object> qsofa = (Map<String, Object>) riskScores.getOrDefault("qsofa", Map.of());
        findings.add("qSOFA: " + qsofa.getOrDefault("score", 0) + "/3");

        Map<String, Object> mews = (Map<String, Object>) riskScores.getOrDefault("mews", Map.of());
        findings.add("MEWS: " + mews.getOrDefault("score", 0) + " (" +
                mews.getOrDefault("riskLevel", "unknown") + ")");

        Map<String, Object> sepsis = (Map<String, Object>) riskScores.getOrDefault("sepsisRisk", Map.of());
        findings.add("Sepsis risk: " + sepsis.getOrDefault("score", 0) + "% (" +
                sepsis.getOrDefault("level", "unknown") + ")");

        List<Map<String, Object>> recs = (List<Map<String, Object>>)
                guidelines.getOrDefault("recommendations", List.of());
        for (Map<String, Object> rec : recs) {
            if ("critical".equals(rec.get("priority"))) {
                findings.add("[CRITICAL] " + rec.get("code") + ": " + rec.get("recommendedAction"));
            }
        }

        return findings;
    }
}

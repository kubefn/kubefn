package com.enterprise.clinical;

import io.kubefn.api.*;

import java.util.*;

/**
 * Calculates clinical risk scores based on vitals and lab results.
 * Implements simplified versions of standard scoring systems including
 * qSOFA (Quick Sequential Organ Failure Assessment) for sepsis screening
 * and MEWS (Modified Early Warning Score) for general deterioration.
 */
@FnRoute(path = "/clinical/risk", methods = {"POST"})
@FnGroup("clinical-engine")
public class RiskScoringFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var vitalsData = ctx.heap().get("clinical:vitals", Map.class).orElse(Map.of());
        var labsData = ctx.heap().get("clinical:labs", Map.class).orElse(Map.of());

        String patientId = (String) vitalsData.getOrDefault("patientId",
                labsData.getOrDefault("patientId", "unknown"));
        Map<String, Map<String, Object>> vitals = (Map<String, Map<String, Object>>)
                vitalsData.getOrDefault("vitals", Map.of());
        List<Map<String, Object>> labResults = (List<Map<String, Object>>)
                labsData.getOrDefault("labResults", List.of());

        // Extract vital values safely
        double heartRate = extractVitalValue(vitals, "heartRate", 80);
        double systolicBP = extractVitalValue(vitals, "systolicBP", 120);
        double respiratoryRate = extractVitalValue(vitals, "respiratoryRate", 16);
        double temperature = extractVitalValue(vitals, "temperature", 37.0);
        double spO2 = extractVitalValue(vitals, "spO2", 98);

        // Extract lab values safely
        double wbc = extractLabValue(labResults, "wbc", 7.0);
        double lactate = extractLabValue(labResults, "lactate", 1.0);
        double procalcitonin = extractLabValue(labResults, "procalcitonin", 0.05);
        double creatinine = extractLabValue(labResults, "creatinine", 0.9);

        // qSOFA Score (Quick SOFA for sepsis screening)
        int qsofa = 0;
        List<String> qsofaCriteria = new ArrayList<>();
        if (systolicBP <= 100) {
            qsofa++;
            qsofaCriteria.add("Systolic BP <= 100 mmHg (" + systolicBP + ")");
        }
        if (respiratoryRate >= 22) {
            qsofa++;
            qsofaCriteria.add("Respiratory rate >= 22 (" + respiratoryRate + ")");
        }
        // GCS component simplified: assume altered mental status if HR is very high
        if (heartRate > 130) {
            qsofa++;
            qsofaCriteria.add("Altered mental status indicator (HR > 130: " + heartRate + ")");
        }

        // MEWS (Modified Early Warning Score)
        int mews = 0;
        List<String> mewsBreakdown = new ArrayList<>();

        // Heart rate component
        int hrScore = heartRate < 40 ? 2 : (heartRate <= 50 ? 1 :
                (heartRate <= 100 ? 0 : (heartRate <= 110 ? 1 :
                        (heartRate <= 130 ? 2 : 3))));
        mews += hrScore;
        mewsBreakdown.add("HR(" + heartRate + ")=" + hrScore);

        // Systolic BP component
        int bpScore = systolicBP <= 70 ? 3 : (systolicBP <= 80 ? 2 :
                (systolicBP <= 100 ? 1 : (systolicBP <= 199 ? 0 : 2)));
        mews += bpScore;
        mewsBreakdown.add("SBP(" + systolicBP + ")=" + bpScore);

        // Respiratory rate component
        int rrScore = respiratoryRate < 9 ? 2 : (respiratoryRate <= 14 ? 0 :
                (respiratoryRate <= 20 ? 1 : (respiratoryRate <= 29 ? 2 : 3)));
        mews += rrScore;
        mewsBreakdown.add("RR(" + respiratoryRate + ")=" + rrScore);

        // Temperature component
        int tempScore = temperature < 35.0 ? 2 : (temperature <= 38.4 ? 0 : 2);
        mews += tempScore;
        mewsBreakdown.add("Temp(" + temperature + ")=" + tempScore);

        // Sepsis composite risk
        double sepsisRisk = 0.0;
        List<String> sepsisIndicators = new ArrayList<>();
        if (qsofa >= 2) { sepsisRisk += 30; sepsisIndicators.add("qSOFA >= 2"); }
        if (lactate > 2.0) { sepsisRisk += 25; sepsisIndicators.add("Elevated lactate: " + lactate); }
        if (procalcitonin > 0.5) { sepsisRisk += 20; sepsisIndicators.add("Elevated procalcitonin: " + procalcitonin); }
        if (wbc > 12 || wbc < 4) { sepsisRisk += 15; sepsisIndicators.add("Abnormal WBC: " + wbc); }
        if (temperature > 38.3 || temperature < 36.0) { sepsisRisk += 10; sepsisIndicators.add("Temperature abnormal: " + temperature); }
        sepsisRisk = Math.min(sepsisRisk, 100);

        String sepsisRiskLevel = sepsisRisk >= 60 ? "high" :
                (sepsisRisk >= 30 ? "moderate" : (sepsisRisk > 0 ? "low" : "minimal"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("patientId", patientId);
        result.put("qsofa", Map.of(
                "score", qsofa,
                "maxScore", 3,
                "criteria", qsofaCriteria,
                "sepsisScreenPositive", qsofa >= 2
        ));
        result.put("mews", Map.of(
                "score", mews,
                "breakdown", mewsBreakdown,
                "riskLevel", mews >= 5 ? "high" : (mews >= 3 ? "moderate" : "low"),
                "clinicalAction", mews >= 5 ? "immediate_review" :
                        (mews >= 3 ? "increase_monitoring" : "routine_monitoring")
        ));
        result.put("sepsisRisk", Map.of(
                "score", sepsisRisk,
                "level", sepsisRiskLevel,
                "indicators", sepsisIndicators
        ));
        result.put("overallRiskLevel", determineOverallRisk(qsofa, mews, sepsisRisk));
        result.put("scoredAt", System.currentTimeMillis());

        ctx.heap().publish("clinical:risk-scores", result, Map.class);
        return KubeFnResponse.ok(result);
    }

    private double extractVitalValue(Map<String, Map<String, Object>> vitals, String name, double defaultVal) {
        Map<String, Object> vital = vitals.get(name);
        if (vital == null) return defaultVal;
        Object value = vital.get("value");
        return value instanceof Number ? ((Number) value).doubleValue() : defaultVal;
    }

    private double extractLabValue(List<Map<String, Object>> labs, String testName, double defaultVal) {
        for (Map<String, Object> lab : labs) {
            if (testName.equals(lab.get("test"))) {
                Object value = lab.get("value");
                return value instanceof Number ? ((Number) value).doubleValue() : defaultVal;
            }
        }
        return defaultVal;
    }

    private String determineOverallRisk(int qsofa, int mews, double sepsisRisk) {
        if (qsofa >= 2 || mews >= 5 || sepsisRisk >= 60) return "critical";
        if (mews >= 3 || sepsisRisk >= 30) return "elevated";
        if (qsofa >= 1 || mews >= 2 || sepsisRisk > 0) return "moderate";
        return "low";
    }
}

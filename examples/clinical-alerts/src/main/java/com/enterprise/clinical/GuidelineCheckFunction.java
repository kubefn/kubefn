package com.enterprise.clinical;

import com.kubefn.api.*;

import java.util.*;

/**
 * Checks patient data against clinical practice guidelines and evidence-based
 * protocols. Evaluates Surviving Sepsis Campaign bundles, critical lab value
 * response protocols, and vital sign intervention thresholds.
 */
@FnRoute(path = "/clinical/guidelines", methods = {"POST"})
@FnGroup("clinical-engine")
public class GuidelineCheckFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var vitalsData = ctx.heap().get("clinical:vitals", Map.class).orElse(Map.of());
        var labsData = ctx.heap().get("clinical:labs", Map.class).orElse(Map.of());
        var riskScores = ctx.heap().get("clinical:risk-scores", Map.class).orElse(Map.of());

        String patientId = (String) vitalsData.getOrDefault("patientId",
                riskScores.getOrDefault("patientId", "unknown"));

        Map<String, Map<String, Object>> vitals = (Map<String, Map<String, Object>>)
                vitalsData.getOrDefault("vitals", Map.of());
        List<Map<String, Object>> labResults = (List<Map<String, Object>>)
                labsData.getOrDefault("labResults", List.of());

        List<Map<String, Object>> guidelineViolations = new ArrayList<>();
        List<Map<String, Object>> recommendations = new ArrayList<>();

        // 1. Sepsis bundle compliance (Surviving Sepsis Campaign)
        Map<String, Object> sepsisRisk = (Map<String, Object>)
                riskScores.getOrDefault("sepsisRisk", Map.of());
        String sepsisLevel = (String) sepsisRisk.getOrDefault("level", "minimal");

        if ("high".equals(sepsisLevel) || "moderate".equals(sepsisLevel)) {
            // Hour-1 Bundle requirements
            recommendations.add(guideline("SSC-001", "Surviving Sepsis Campaign Hour-1 Bundle",
                    "Measure lactate level. Re-measure if initial lactate > 2 mmol/L.",
                    "high", "evidence_level_1"));
            recommendations.add(guideline("SSC-002", "SSC Hour-1 Bundle",
                    "Obtain blood cultures before administering antibiotics.",
                    "high", "evidence_level_1"));
            recommendations.add(guideline("SSC-003", "SSC Hour-1 Bundle",
                    "Administer broad-spectrum antibiotics within 1 hour.",
                    "high", "evidence_level_1"));

            double systolicBP = extractVitalValue(vitals, "systolicBP", 120);
            if (systolicBP < 90) {
                recommendations.add(guideline("SSC-004", "SSC Fluid Resuscitation",
                        "Administer 30mL/kg crystalloid for hypotension (SBP=" + systolicBP + " mmHg).",
                        "critical", "evidence_level_1"));
            }
        }

        // 2. Critical lab value response protocols
        for (Map<String, Object> lab : labResults) {
            String classification = (String) lab.getOrDefault("classification", "normal");
            String testName = (String) lab.get("test");
            double value = ((Number) lab.get("value")).doubleValue();

            if ("critical".equals(classification)) {
                guidelineViolations.add(Map.of(
                        "protocol", "CRIT-LAB-001",
                        "description", "Critical lab value requires immediate physician notification",
                        "test", testName,
                        "value", value,
                        "flag", lab.get("flag"),
                        "requiredAction", "Notify attending physician within 30 minutes",
                        "priority", "critical"
                ));
            }

            // Specific guideline checks
            if ("potassium".equals(testName)) {
                if (value > 6.0) {
                    recommendations.add(guideline("ELEC-001", "Hyperkalemia Protocol",
                            "K+ " + value + " mEq/L: Obtain stat ECG, consider calcium gluconate, insulin/glucose.",
                            "critical", "standard_of_care"));
                } else if (value < 3.0) {
                    recommendations.add(guideline("ELEC-002", "Hypokalemia Protocol",
                            "K+ " + value + " mEq/L: IV potassium replacement with cardiac monitoring.",
                            "high", "standard_of_care"));
                }
            }

            if ("glucose".equals(testName)) {
                if (value < 54) {
                    recommendations.add(guideline("GLYC-001", "Severe Hypoglycemia Protocol",
                            "Glucose " + value + " mg/dL: Administer 25g dextrose IV. Recheck in 15 min.",
                            "critical", "standard_of_care"));
                } else if (value > 300) {
                    recommendations.add(guideline("GLYC-002", "Hyperglycemia Management",
                            "Glucose " + value + " mg/dL: Check ketones. Consider insulin drip protocol.",
                            "high", "standard_of_care"));
                }
            }

            if ("troponin".equals(testName) && value > 0.04) {
                recommendations.add(guideline("CARD-001", "Elevated Troponin Protocol",
                        "Troponin " + value + " ng/mL: Serial troponins q6h, 12-lead ECG, cardiology consult.",
                        "critical", "evidence_level_1"));
            }
        }

        // 3. Vital sign intervention thresholds
        double spO2 = extractVitalValue(vitals, "spO2", 98);
        if (spO2 < 92) {
            recommendations.add(guideline("RESP-001", "Oxygen Supplementation",
                    "SpO2 " + spO2 + "%: Initiate supplemental oxygen. Target SpO2 92-96%.",
                    spO2 < 88 ? "critical" : "high", "standard_of_care"));
        }

        double heartRate = extractVitalValue(vitals, "heartRate", 80);
        if (heartRate > 150 || heartRate < 40) {
            recommendations.add(guideline("CARD-002", "Heart Rate Alert Protocol",
                    "HR " + heartRate + " bpm: Obtain 12-lead ECG. Assess hemodynamic stability.",
                    "critical", "standard_of_care"));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("patientId", patientId);
        result.put("guidelineViolations", guidelineViolations);
        result.put("recommendations", recommendations);
        result.put("violationCount", guidelineViolations.size());
        result.put("recommendationCount", recommendations.size());
        result.put("highestPriority", determineHighestPriority(guidelineViolations, recommendations));
        result.put("evaluatedAt", System.currentTimeMillis());

        ctx.heap().publish("clinical:guideline-check", result, Map.class);
        return KubeFnResponse.ok(result);
    }

    private Map<String, Object> guideline(String code, String name, String action,
                                           String priority, String evidenceLevel) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("code", code);
        g.put("guideline", name);
        g.put("recommendedAction", action);
        g.put("priority", priority);
        g.put("evidenceLevel", evidenceLevel);
        return g;
    }

    private double extractVitalValue(Map<String, Map<String, Object>> vitals, String name, double defaultVal) {
        Map<String, Object> vital = vitals.get(name);
        if (vital == null) return defaultVal;
        Object value = vital.get("value");
        return value instanceof Number ? ((Number) value).doubleValue() : defaultVal;
    }

    private String determineHighestPriority(List<Map<String, Object>> violations,
                                             List<Map<String, Object>> recommendations) {
        for (var v : violations) {
            if ("critical".equals(v.get("priority"))) return "critical";
        }
        for (var r : recommendations) {
            if ("critical".equals(r.get("priority"))) return "critical";
        }
        for (var v : violations) {
            if ("high".equals(v.get("priority"))) return "high";
        }
        for (var r : recommendations) {
            if ("high".equals(r.get("priority"))) return "high";
        }
        if (!violations.isEmpty() || !recommendations.isEmpty()) return "moderate";
        return "low";
    }
}

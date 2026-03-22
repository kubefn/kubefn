package com.enterprise.clinical;

import io.kubefn.api.*;

import java.util.*;

/**
 * Processes laboratory results and classifies them as normal, abnormal,
 * or critical based on standard clinical reference ranges. Tracks trending
 * for key biomarkers.
 */
@FnRoute(path = "/clinical/labs", methods = {"POST"})
@FnGroup("clinical-engine")
public class LabResultsFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    // Reference ranges: test -> [low, high, criticalLow, criticalHigh, unit]
    private static final Map<String, double[]> REFERENCE_RANGES = Map.ofEntries(
            Map.entry("wbc", new double[]{4.5, 11.0, 2.0, 30.0}),        // x10^3/uL
            Map.entry("hemoglobin", new double[]{12.0, 17.5, 7.0, 20.0}), // g/dL
            Map.entry("platelets", new double[]{150, 400, 50, 1000}),      // x10^3/uL
            Map.entry("creatinine", new double[]{0.6, 1.2, 0.3, 10.0}),   // mg/dL
            Map.entry("lactate", new double[]{0.5, 2.0, 0.0, 12.0}),      // mmol/L
            Map.entry("procalcitonin", new double[]{0.0, 0.1, 0.0, 100.0}), // ng/mL
            Map.entry("glucose", new double[]{70, 100, 40, 500}),          // mg/dL
            Map.entry("potassium", new double[]{3.5, 5.0, 2.5, 6.5}),     // mEq/L
            Map.entry("sodium", new double[]{136, 145, 120, 160}),         // mEq/L
            Map.entry("bilirubin", new double[]{0.1, 1.2, 0.0, 15.0}),    // mg/dL
            Map.entry("troponin", new double[]{0.0, 0.04, 0.0, 10.0})     // ng/mL
    );

    private static final Map<String, String> UNITS = Map.ofEntries(
            Map.entry("wbc", "x10^3/uL"), Map.entry("hemoglobin", "g/dL"),
            Map.entry("platelets", "x10^3/uL"), Map.entry("creatinine", "mg/dL"),
            Map.entry("lactate", "mmol/L"), Map.entry("procalcitonin", "ng/mL"),
            Map.entry("glucose", "mg/dL"), Map.entry("potassium", "mEq/L"),
            Map.entry("sodium", "mEq/L"), Map.entry("bilirubin", "mg/dL"),
            Map.entry("troponin", "ng/mL")
    );

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        String patientId = request.queryParam("patientId").orElse("unknown");

        List<Map<String, Object>> labResults = new ArrayList<>();
        int criticalCount = 0;
        int abnormalCount = 0;

        for (var entry : REFERENCE_RANGES.entrySet()) {
            String testName = entry.getKey();
            Optional<String> param = request.queryParam(testName);
            if (param.isEmpty()) continue;

            double value;
            try {
                value = Double.parseDouble(param.get());
            } catch (NumberFormatException e) {
                continue;
            }

            double[] range = entry.getValue();
            double normalLow = range[0], normalHigh = range[1];
            double criticalLow = range[2], criticalHigh = range[3];

            String classification;
            String flag;
            if (value < criticalLow || value > criticalHigh) {
                classification = "critical";
                flag = value < criticalLow ? "CRIT_LOW" : "CRIT_HIGH";
                criticalCount++;
            } else if (value < normalLow || value > normalHigh) {
                classification = "abnormal";
                flag = value < normalLow ? "LOW" : "HIGH";
                abnormalCount++;
            } else {
                classification = "normal";
                flag = "NORMAL";
            }

            Map<String, Object> labResult = new LinkedHashMap<>();
            labResult.put("test", testName);
            labResult.put("value", value);
            labResult.put("unit", UNITS.getOrDefault(testName, "unknown"));
            labResult.put("referenceRange", normalLow + " - " + normalHigh);
            labResult.put("classification", classification);
            labResult.put("flag", flag);
            labResult.put("percentFromNormal", calculatePercentFromNormal(value, normalLow, normalHigh));

            labResults.add(labResult);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("patientId", patientId);
        result.put("labResults", labResults);
        result.put("testCount", labResults.size());
        result.put("criticalCount", criticalCount);
        result.put("abnormalCount", abnormalCount);
        result.put("normalCount", labResults.size() - criticalCount - abnormalCount);
        result.put("overallStatus", criticalCount > 0 ? "critical" :
                (abnormalCount > 0 ? "abnormal" : "normal"));
        result.put("processedAt", System.currentTimeMillis());

        ctx.heap().publish("clinical:labs", result, Map.class);
        return KubeFnResponse.ok(result);
    }

    private double calculatePercentFromNormal(double value, double low, double high) {
        double midpoint = (low + high) / 2.0;
        double range = (high - low) / 2.0;
        if (range == 0) return 0;
        double deviation = ((value - midpoint) / range) * 100;
        return Math.round(deviation * 10.0) / 10.0;
    }
}

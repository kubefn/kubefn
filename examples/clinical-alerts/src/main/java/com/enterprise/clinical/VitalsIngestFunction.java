package com.enterprise.clinical;

import io.kubefn.api.*;

import java.util.*;

/**
 * Ingests patient vital signs. Validates physiological plausibility,
 * applies unit conversions, and normalizes into a standard clinical format
 * with timestamp and measurement source tracking.
 */
@FnRoute(path = "/clinical/vitals", methods = {"POST"})
@FnGroup("clinical-engine")
public class VitalsIngestFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    // Physiological plausibility ranges (reject clearly erroneous readings)
    private static final Map<String, double[]> PLAUSIBILITY_RANGES = Map.of(
            "heartRate", new double[]{20, 300},        // bpm
            "systolicBP", new double[]{40, 300},       // mmHg
            "diastolicBP", new double[]{20, 200},      // mmHg
            "temperature", new double[]{25.0, 45.0},   // Celsius
            "spO2", new double[]{50, 100},             // percent
            "respiratoryRate", new double[]{4, 60}     // breaths/min
    );

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        String patientId = request.queryParam("patientId").orElse("");
        if (patientId.isBlank()) {
            return KubeFnResponse.badRequest(Map.of("error", "missing_patient_id",
                    "message", "patientId is required"));
        }

        // Parse vital signs from query params
        Map<String, Object> vitals = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        List<String> rejected = new ArrayList<>();

        parseVital(request, "heartRate", "bpm", vitals, warnings, rejected);
        parseVital(request, "systolicBP", "mmHg", vitals, warnings, rejected);
        parseVital(request, "diastolicBP", "mmHg", vitals, warnings, rejected);
        parseVital(request, "respiratoryRate", "breaths/min", vitals, warnings, rejected);
        parseVital(request, "spO2", "%", vitals, warnings, rejected);

        // Temperature with Fahrenheit auto-conversion
        Optional<String> tempParam = request.queryParam("temperature");
        if (tempParam.isPresent()) {
            double tempValue = Double.parseDouble(tempParam.get());
            String tempUnit = request.queryParam("tempUnit").orElse("C");
            if ("F".equalsIgnoreCase(tempUnit)) {
                tempValue = (tempValue - 32) * 5.0 / 9.0; // Convert to Celsius
                warnings.add("Temperature converted from Fahrenheit to Celsius");
            }
            double[] range = PLAUSIBILITY_RANGES.get("temperature");
            if (tempValue >= range[0] && tempValue <= range[1]) {
                vitals.put("temperature", Map.of(
                        "value", Math.round(tempValue * 10.0) / 10.0,
                        "unit", "celsius"
                ));
            } else {
                rejected.add("temperature=" + tempValue + " outside plausible range");
            }
        }

        if (vitals.isEmpty()) {
            return KubeFnResponse.badRequest(Map.of("error", "no_valid_vitals",
                    "message", "At least one valid vital sign is required",
                    "rejected", rejected));
        }

        String measurementSource = request.queryParam("source").orElse("bedside_monitor");
        String encounterId = request.queryParam("encounterId")
                .orElse("ENC-" + patientId + "-" + System.currentTimeMillis() % 10000);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("patientId", patientId);
        result.put("encounterId", encounterId);
        result.put("vitals", vitals);
        result.put("vitalCount", vitals.size());
        result.put("measurementSource", measurementSource);
        result.put("measurementTimestamp", System.currentTimeMillis());
        result.put("warnings", warnings);
        result.put("rejected", rejected);
        result.put("dataQuality", rejected.isEmpty() ? "complete" : "partial");

        ctx.heap().publish("clinical:vitals", result, Map.class);
        return KubeFnResponse.ok(result);
    }

    private void parseVital(KubeFnRequest request, String name, String unit,
                            Map<String, Object> vitals, List<String> warnings,
                            List<String> rejected) {
        request.queryParam(name).ifPresent(val -> {
            try {
                double value = Double.parseDouble(val);
                double[] range = PLAUSIBILITY_RANGES.get(name);
                if (range != null && (value < range[0] || value > range[1])) {
                    rejected.add(name + "=" + value + " outside plausible range [" +
                            range[0] + "-" + range[1] + "]");
                } else {
                    vitals.put(name, Map.of("value", value, "unit", unit));
                }
            } catch (NumberFormatException e) {
                rejected.add(name + "=" + val + " is not a valid number");
            }
        });
    }
}

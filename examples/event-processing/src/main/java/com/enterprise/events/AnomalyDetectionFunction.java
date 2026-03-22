package com.enterprise.events;

import io.kubefn.api.*;

import java.time.Duration;
import java.util.*;

/**
 * Detects anomalies in the event stream using a combination of threshold-based
 * rules, rate-of-change analysis, and sliding-window statistics maintained
 * in FnCache. Classifies anomalies by severity.
 */
@FnRoute(path = "/events/anomaly", methods = {"POST"})
@FnGroup("event-processor")
public class AnomalyDetectionFunction implements KubeFnHandler, FnContextAware {
    private FnContext ctx;

    private static final Duration HISTORY_TTL = Duration.ofMinutes(30);
    private static final int HISTORY_WINDOW_SIZE = 20;
    private static final double RATE_OF_CHANGE_THRESHOLD = 0.30; // 30% change is suspicious

    @Override
    public void setContext(FnContext context) { this.ctx = context; }

    @Override
    @SuppressWarnings("unchecked")
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        var enriched = ctx.heap().get("event:enriched", Map.class).orElse(Map.of());

        String deviceId = (String) enriched.getOrDefault("deviceId", "unknown");
        String eventType = (String) enriched.getOrDefault("eventType", "unknown");
        Object rawValue = enriched.get("value");
        Map<String, Double> thresholds = (Map<String, Double>) enriched.get("thresholds");

        List<Map<String, Object>> anomalies = new ArrayList<>();
        String overallSeverity = "none";

        if (rawValue instanceof Number numericValue) {
            double value = numericValue.doubleValue();

            // 1. Threshold-based anomaly detection
            if (thresholds != null) {
                double warningHigh = thresholds.getOrDefault("warningHigh", Double.MAX_VALUE);
                double warningLow = thresholds.getOrDefault("warningLow", -Double.MAX_VALUE);
                double criticalHigh = thresholds.getOrDefault("criticalHigh", Double.MAX_VALUE);
                double criticalLow = thresholds.getOrDefault("criticalLow", -Double.MAX_VALUE);

                if (value >= criticalHigh || value <= criticalLow) {
                    anomalies.add(Map.of(
                            "type", "threshold_breach",
                            "severity", "critical",
                            "message", String.format("Value %.2f is outside critical bounds [%.1f, %.1f]",
                                    value, criticalLow, criticalHigh),
                            "value", value,
                            "bound", value >= criticalHigh ? "upper_critical" : "lower_critical"
                    ));
                    overallSeverity = "critical";
                } else if (value >= warningHigh || value <= warningLow) {
                    anomalies.add(Map.of(
                            "type", "threshold_breach",
                            "severity", "warning",
                            "message", String.format("Value %.2f is outside warning bounds [%.1f, %.1f]",
                                    value, warningLow, warningHigh),
                            "value", value,
                            "bound", value >= warningHigh ? "upper_warning" : "lower_warning"
                    ));
                    if (!"critical".equals(overallSeverity)) overallSeverity = "warning";
                }
            }

            // 2. Rate-of-change detection using cached history
            String historyKey = "history:" + deviceId + ":" + eventType;
            List<Double> history = ctx.cache().get(historyKey, List.class)
                    .orElse(new ArrayList<>());

            if (!history.isEmpty()) {
                double lastValue = history.get(history.size() - 1);
                double rateOfChange = Math.abs(lastValue) > 0.001
                        ? Math.abs((value - lastValue) / lastValue) : 0;

                if (rateOfChange > RATE_OF_CHANGE_THRESHOLD) {
                    String severity = rateOfChange > 0.60 ? "critical" : "warning";
                    anomalies.add(Map.of(
                            "type", "rapid_change",
                            "severity", severity,
                            "message", String.format("Value changed %.1f%% from %.2f to %.2f",
                                    rateOfChange * 100, lastValue, value),
                            "previousValue", lastValue,
                            "currentValue", value,
                            "changePercent", Math.round(rateOfChange * 1000.0) / 10.0
                    ));
                    if ("critical".equals(severity)) overallSeverity = "critical";
                    else if (!"critical".equals(overallSeverity)) overallSeverity = "warning";
                }

                // 3. Statistical anomaly (Z-score approximation)
                if (history.size() >= 5) {
                    double mean = history.stream().mapToDouble(d -> d).average().orElse(0);
                    double variance = history.stream()
                            .mapToDouble(d -> (d - mean) * (d - mean))
                            .average().orElse(0);
                    double stdDev = Math.sqrt(variance);

                    if (stdDev > 0.001) {
                        double zScore = Math.abs((value - mean) / stdDev);
                        if (zScore > 3.0) {
                            anomalies.add(Map.of(
                                    "type", "statistical_outlier",
                                    "severity", "warning",
                                    "message", String.format("Z-score %.2f exceeds threshold (3.0). Mean=%.2f, StdDev=%.2f",
                                            zScore, mean, stdDev),
                                    "zScore", Math.round(zScore * 100.0) / 100.0,
                                    "mean", Math.round(mean * 100.0) / 100.0,
                                    "stdDev", Math.round(stdDev * 100.0) / 100.0
                            ));
                            if (!"critical".equals(overallSeverity)) overallSeverity = "warning";
                        }
                    }
                }
            }

            // Update history window in cache
            List<Double> updatedHistory = new ArrayList<>(history);
            updatedHistory.add(value);
            if (updatedHistory.size() > HISTORY_WINDOW_SIZE) {
                updatedHistory = updatedHistory.subList(
                        updatedHistory.size() - HISTORY_WINDOW_SIZE, updatedHistory.size());
            }
            ctx.cache().put(historyKey, updatedHistory, HISTORY_TTL);
        }

        // Check for unregistered device anomaly
        if (!Boolean.TRUE.equals(enriched.get("deviceKnown"))) {
            anomalies.add(Map.of(
                    "type", "unregistered_device",
                    "severity", "warning",
                    "message", "Event from unregistered device: " + deviceId
            ));
            if (!"critical".equals(overallSeverity)) overallSeverity = "warning";
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventId", enriched.get("eventId"));
        result.put("deviceId", deviceId);
        result.put("eventType", eventType);
        result.put("anomalyDetected", !anomalies.isEmpty());
        result.put("severity", overallSeverity);
        result.put("anomalies", anomalies);
        result.put("anomalyCount", anomalies.size());
        result.put("analyzedAt", System.currentTimeMillis());

        ctx.heap().publish("event:anomaly-result", result, Map.class);
        return KubeFnResponse.ok(result);
    }
}
